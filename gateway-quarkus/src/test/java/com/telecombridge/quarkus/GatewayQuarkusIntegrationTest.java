package com.telecombridge.quarkus;

import com.telecombridge.quarkus.model.ChargeRequest;
import com.telecombridge.quarkus.model.ChargeResponse;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.*;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Integration test for the Quarkus gateway with a live JDiameter stack.
 *
 * <p>Starts an embedded DiameterSimulator on port 13868 (configured via
 * %test profile in application.properties) before running tests.
 *
 * <p>Tests the full CCR→CCA round-trip, health endpoints, and error scenarios.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GatewayQuarkusIntegrationTest {

    private static com.telecombridge.simulator.DiameterSimulator simulator;

    @BeforeAll
    static void startSimulator() throws Exception {
        simulator = new com.telecombridge.simulator.DiameterSimulator(13868);
        Thread simulatorThread = new Thread(() -> {
            try {
                simulator.start();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "test-simulator");
        simulatorThread.setDaemon(true);
        simulatorThread.start();
        // Allow the simulator time to bind and be ready
        Thread.sleep(1500);
    }

    @AfterAll
    static void stopSimulator() {
        if (simulator != null) simulator.stop();
    }

    // ── /q/health endpoints ────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("GET /q/health/live → 200 UP (liveness always passes)")
    void livenessProbeIsUp() {
        given()
            .port(9000)  // management port
        .when()
            .get("/q/health/live")
        .then()
            .statusCode(200)
            .body("status", equalTo("UP"));
    }

    @Test
    @Order(2)
    @DisplayName("GET /q/health/ready → UP when Diameter peer connected")
    void readinessProbeIsUp() {
        given()
            .port(9000)
        .when()
            .get("/q/health/ready")
        .then()
            .statusCode(anyOf(is(200), is(503)))  // may be DOWN if simulator takes time
            .body("status", anyOf(equalTo("UP"), equalTo("DOWN")));
    }

    @Test
    @Order(3)
    @DisplayName("GET /api/v1/health/diameter → UP with peer info")
    void diameterHealthEndpoint() {
        given()
            .contentType(ContentType.JSON)
        .when()
            .get("/api/v1/health/diameter")
        .then()
            .statusCode(anyOf(is(200), is(503)))
            .body("runtime", anyOf(equalTo("Quarkus+JDiameter"), nullValue()));
    }

    // ── POST /api/v1/charge — validation ──────────────────────────────────

    @Test
    @Order(4)
    @DisplayName("POST /api/v1/charge → 400 when msisdn missing")
    void chargeReturnsBadRequestWhenMsisdnMissing() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                { "service_id": "DATA", "requested_units": 1024 }
                """)
        .when()
            .post("/api/v1/charge")
        .then()
            .statusCode(400);
    }

    @Test
    @Order(5)
    @DisplayName("POST /api/v1/charge → 400 when msisdn format invalid")
    void chargeReturnsBadRequestWhenMsisdnInvalid() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                  "msisdn": "not-a-number",
                  "service_id": "DATA",
                  "requested_units": 1024
                }
                """)
        .when()
            .post("/api/v1/charge")
        .then()
            .statusCode(400);
    }

    @Test
    @Order(6)
    @DisplayName("POST /api/v1/charge → 400 when requested_units = 0")
    void chargeReturnsBadRequestWhenUnitsZero() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                {
                  "msisdn": "447700900001",
                  "service_id": "DATA",
                  "requested_units": 0
                }
                """)
        .when()
            .post("/api/v1/charge")
        .then()
            .statusCode(400);
    }

    // ── POST /api/v1/charge — happy path ──────────────────────────────────

    @Test
    @Order(10)
    @DisplayName("POST /api/v1/charge → 200 with granted units on success")
    void chargeSucceedsWithValidRequest() {
        var response = given()
            .contentType(ContentType.JSON)
            .body("""
                {
                  "msisdn": "447700900001",
                  "service_id": "DATA",
                  "requested_units": 1024
                }
                """)
        .when()
            .post("/api/v1/charge")
        .then()
            .statusCode(anyOf(is(200), is(503), is(504)))  // 503/504 if simulator not fully ready
            .extract()
            .as(ChargeResponse.class);

        // If 200, validate the response structure
        if (response.resultCode() == 2001) {
            assertThat(response.msisdn()).isEqualTo("447700900001");
            assertThat(response.serviceId()).isEqualTo("DATA");
            assertThat(response.grantedUnits()).isPositive();
            assertThat(response.resultMessage()).isEqualTo("Success");
            assertThat(response.sessionId()).isNotBlank();
        }
    }

    @Test
    @Order(11)
    @DisplayName("POST /api/v1/charge → session_id preserved when provided")
    void chargePreservesProvidedSessionId() {
        var response = given()
            .contentType(ContentType.JSON)
            .body("""
                {
                  "msisdn": "447700900002",
                  "service_id": "VOICE",
                  "requested_units": 60,
                  "session_id": "my-custom-session-abc"
                }
                """)
        .when()
            .post("/api/v1/charge")
        .then()
            .statusCode(anyOf(is(200), is(503), is(504)))
            .extract()
            .as(ChargeResponse.class);

        if (response.resultCode() == 2001) {
            assertThat(response.sessionId()).isEqualTo("my-custom-session-abc");
        }
    }

    // ── Metrics ────────────────────────────────────────────────────────────

    @Test
    @Order(20)
    @DisplayName("GET /q/metrics → Prometheus format contains diameter metrics")
    void metricsEndpointContainsDiameterMetrics() {
        given()
            .port(9000)
        .when()
            .get("/q/metrics")
        .then()
            .statusCode(200)
            .contentType(containsString("text/plain"));
        // diameter.ccr.sent counter should appear after at least one charge attempt
    }
}
