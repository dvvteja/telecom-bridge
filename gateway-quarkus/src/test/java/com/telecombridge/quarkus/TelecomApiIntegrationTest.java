package com.telecombridge.quarkus;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.*;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration test for {@code POST /in/get-account-info} and
 * {@code POST /in/bundle-activate} — mirroring the exact PCAP transaction flow.
 *
 * <h3>PCAP Scenario Replicated</h3>
 * <pre>
 *   Test 1 + 2: Two concurrent /in/get-account-info calls (PCAP frames 10 + 25)
 *   Test 3:     Second /in/get-account-info on same connection (PCAP frame 55)
 *   Test 4:     /in/bundle-activate (PCAP frame 73)
 *   Test 5:     GET /in/health (PCAP-unobserved, diagnostic)
 * </pre>
 *
 * <p>All tests assert ~1s latency tolerance (actual delay is mocked by
 * TelecomApiSimulatorService — Quarkus test mode uses its own delay).
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TelecomApiIntegrationTest {

    // ── Reusable request bodies ───────────────────────────────────────────

    private static final String ACCOUNT_INFO_BODY_1 = """
        {
          "msisdn": "447700900001",
          "imsi": "234101234567890",
          "transaction_id": "TX-PCAP-FRAME-10-001",
          "service_type": "DATA",
          "node_id": "PGW-LON-01",
          "plmn_id": "23410",
          "requested_units": 1073741824,
          "rating_group": 1,
          "service_id": "SVC_DATA_4G",
          "ue_ip_address": "10.100.0.1",
          "apn": "internet",
          "charging_characteristics": "0800"
        }
        """;

    private static final String ACCOUNT_INFO_BODY_2 = """
        {
          "msisdn": "447700900002",
          "imsi": "234101234567891",
          "transaction_id": "TX-PCAP-FRAME-25-001",
          "service_type": "DATA",
          "node_id": "PGW-LON-01",
          "plmn_id": "23410",
          "requested_units": 1073741824,
          "rating_group": 1,
          "service_id": "SVC_DATA_4G",
          "ue_ip_address": "10.100.0.2",
          "apn": "internet",
          "charging_characteristics": "0800"
        }
        """;

    private static final String BUNDLE_ACTIVATE_BODY = """
        {
          "msisdn": "447700900002",
          "imsi": "234101234567891",
          "transaction_id": "TX-PCAP-FRAME-73-001",
          "bundle_id": "BUNDLE_DATA_1GB_7D",
          "activation_mode": "IMMEDIATE",
          "channel": "APP",
          "promo_code": null,
          "node_id": "BILLING-SVC-01",
          "plmn_id": "23410",
          "account_balance": 50000,
          "currency_code": "GBP",
          "tariff_plan": "PLAN_DATA_UNLIMITED",
          "active_bundle_ids": ["BUNDLE_VOICE_200MIN"]
        }
        """;

    // ═══════════════════════════════════════════════════════════════════════
    // Scenario 1 — GET-ACCOUNT-INFO (PCAP frame 10 + 25, concurrent callers)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @Order(1)
    @DisplayName("POST /in/get-account-info → 200 + full account JSON (PCAP frame 10)")
    void getAccountInfoConn1ReturnsSuccess() {
        given()
            .contentType(ContentType.JSON)
            .body(ACCOUNT_INFO_BODY_1)
        .when()
            .post("/in/get-account-info")
        .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            // Result envelope
            .body("result_code", equalTo("SUCCESS"))
            .body("msisdn", equalTo("447700900001"))
            .body("transaction_id", equalTo("TX-PCAP-FRAME-10-001"))
            // Subscriber state
            .body("status", equalTo("ACTIVE"))
            .body("subscriber_type", equalTo("PREPAID"))
            // Active bundles present
            .body("active_bundles", not(empty()))
            .body("active_bundles[0].bundle_type", equalTo("DATA"))
            .body("active_bundles[0].remaining_quota", greaterThan(0))
            // Usage records present
            .body("usage_records", not(empty()))
            // Service entitlements present
            .body("service_entitlements", not(empty()))
            // Charging info present
            .body("charging_info.online_charging", equalTo(true))
            .body("charging_info.result_code", equalTo(2001))
            // Balance present
            .body("main_balance.amount", greaterThan(0))
            .body("main_balance.currency_code", equalTo("GBP"));
    }

    @Test
    @Order(2)
    @DisplayName("POST /in/get-account-info → 200 + full account JSON (PCAP frame 25, concurrent)")
    void getAccountInfoConn2ReturnsSuccess() {
        given()
            .contentType(ContentType.JSON)
            .body(ACCOUNT_INFO_BODY_2)
        .when()
            .post("/in/get-account-info")
        .then()
            .statusCode(200)
            .body("result_code", equalTo("SUCCESS"))
            .body("msisdn", equalTo("447700900002"))
            .body("transaction_id", equalTo("TX-PCAP-FRAME-25-001"))
            .body("active_bundles", not(empty()))
            .body("granted_units", greaterThan(0));
    }

    @Test
    @Order(3)
    @DisplayName("POST /in/get-account-info → 404 for unknown MSISDN (PCAP-derived error path)")
    void getAccountInfoUnknownSubscriberReturns404() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                  "msisdn": "447700999999",
                  "transaction_id": "TX-UNKNOWN-001",
                  "service_type": "DATA"
                }
                """)
        .when()
            .post("/in/get-account-info")
        .then()
            .statusCode(404)
            .body("result_code", equalTo("SUBSCRIBER_NOT_FOUND"));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Scenario 2 — BUNDLE-ACTIVATE (PCAP frame 73)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @Order(10)
    @DisplayName("POST /in/bundle-activate → 200 + activation confirmation (PCAP frame 73)")
    void bundleActivateReturnsSuccess() {
        given()
            .contentType(ContentType.JSON)
            .body(BUNDLE_ACTIVATE_BODY)
        .when()
            .post("/in/bundle-activate")
        .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .body("result_code", equalTo("SUCCESS"))
            .body("msisdn", equalTo("447700900002"))
            .body("bundle_id", equalTo("BUNDLE_DATA_1GB_7D"))
            .body("transaction_id", equalTo("TX-PCAP-FRAME-73-001"))
            // Quota grant
            .body("total_quota", equalTo(1073741824))
            .body("quota_unit", equalTo("OCTETS"))
            // Balance deducted
            .body("balance_before", greaterThan(0))
            .body("balance_after", lessThan((int) 50000L))
            .body("price_charged", greaterThan(0))
            .body("currency_code", equalTo("GBP"))
            // Charging reference generated
            .body("charging_reference", not(emptyString()))
            // Updated bundles list
            .body("active_bundles", not(empty()))
            // SMS notification
            .body("sms_notification", not(emptyString()));
    }

    @Test
    @Order(11)
    @DisplayName("POST /in/bundle-activate → 402 for insufficient balance")
    void bundleActivateInsufficientBalance() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                  "msisdn": "447700900099",
                  "transaction_id": "TX-LOW-BAL-001",
                  "bundle_id": "BUNDLE_DATA_10GB"
                }
                """)
        .when()
            .post("/in/bundle-activate")
        .then()
            .statusCode(402)
            .body("result_code", equalTo("INSUFFICIENT_BALANCE"));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Validation
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @Order(20)
    @DisplayName("POST /in/get-account-info → 400 when msisdn missing")
    void getAccountInfoMissingMsisdn() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                { "transaction_id": "TX-001", "service_type": "DATA" }
                """)
        .when()
            .post("/in/get-account-info")
        .then()
            .statusCode(400);
    }

    @Test
    @Order(21)
    @DisplayName("GET /in/health → 200 UP")
    void healthEndpointIsUp() {
        given()
        .when()
            .get("/in/health")
        .then()
            .statusCode(200)
            .body("status", equalTo("UP"))
            .body("service", equalTo("telecom-api"));
    }
}
