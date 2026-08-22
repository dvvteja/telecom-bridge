package com.telecombridge.quarkus.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Response for {@code POST /in/get-account-info}.
 *
 * <p>Modelled to produce ~5,100–5,200 bytes of JSON — matching the
 * 4-segment reassembled TCP payload seen in PCAP frames 31/37/43/49/61/67.
 *
 * <p>Contains full subscriber account state: balance, active bundles,
 * quota counters, usage records, and service entitlements.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AccountInfoResponse(

    // ── Correlation ───────────────────────────────────────────────────────
    @JsonProperty("transaction_id")      String transactionId,
    @JsonProperty("result_code")         String resultCode,
    @JsonProperty("result_description")  String resultDescription,
    @JsonProperty("timestamp")           String timestamp,

    // ── Subscriber Identity ───────────────────────────────────────────────
    @JsonProperty("msisdn")              String msisdn,
    @JsonProperty("imsi")                String imsi,
    @JsonProperty("account_id")          String accountId,
    @JsonProperty("subscriber_type")     String subscriberType,   // PREPAID / POSTPAID / HYBRID
    @JsonProperty("tariff_plan")         String tariffPlan,
    @JsonProperty("tariff_plan_name")    String tariffPlanName,
    @JsonProperty("status")              String status,            // ACTIVE / SUSPENDED / BARRED
    @JsonProperty("language_code")       String languageCode,
    @JsonProperty("activation_date")     String activationDate,
    @JsonProperty("expiry_date")         String expiryDate,

    // ── Balance ───────────────────────────────────────────────────────────
    @JsonProperty("main_balance")        BalanceInfo mainBalance,
    @JsonProperty("bonus_balance")       BalanceInfo bonusBalance,
    @JsonProperty("loan_balance")        BalanceInfo loanBalance,

    // ── Quota / Granted Units ─────────────────────────────────────────────
    @JsonProperty("granted_units")       Long grantedUnits,
    @JsonProperty("validity_time")       Integer validityTime,     // seconds
    @JsonProperty("final_unit_action")   String finalUnitAction,   // TERMINATE / REDIRECT

    // ── Active Bundles ────────────────────────────────────────────────────
    @JsonProperty("active_bundles")      List<BundleInfo> activeBundles,

    // ── Usage Records ────────────────────────────────────────────────────
    @JsonProperty("usage_records")       List<UsageRecord> usageRecords,

    // ── Service Entitlements ─────────────────────────────────────────────
    @JsonProperty("service_entitlements") List<ServiceEntitlement> serviceEntitlements,

    // ── Charging Info ────────────────────────────────────────────────────
    @JsonProperty("charging_info")       ChargingInfo chargingInfo,

    // ── Network Info ─────────────────────────────────────────────────────
    @JsonProperty("home_plmn")           String homePlmn,
    @JsonProperty("serving_plmn")        String servingPlmn,
    @JsonProperty("roaming")             Boolean roaming,

    // ── Error (only populated on non-SUCCESS) ─────────────────────────────
    @JsonProperty("error_detail")        String errorDetail

) {

    // ── Nested record types ─────────────────────────────────────────────────

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BalanceInfo(
        @JsonProperty("amount")        Long amount,        // minor currency units (pence, paisa)
        @JsonProperty("currency_code") String currencyCode,
        @JsonProperty("expiry_date")   String expiryDate
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BundleInfo(
        @JsonProperty("bundle_id")          String bundleId,
        @JsonProperty("bundle_name")        String bundleName,
        @JsonProperty("bundle_type")        String bundleType,    // DATA / VOICE / SMS / COMBO
        @JsonProperty("service_type")       String serviceType,
        @JsonProperty("total_quota")        Long totalQuota,      // octets or seconds
        @JsonProperty("remaining_quota")    Long remainingQuota,
        @JsonProperty("used_quota")         Long usedQuota,
        @JsonProperty("quota_unit")         String quotaUnit,     // OCTETS / SECONDS
        @JsonProperty("activation_date")    String activationDate,
        @JsonProperty("expiry_date")        String expiryDate,
        @JsonProperty("auto_renewal")       Boolean autoRenewal,
        @JsonProperty("renewal_price")      Long renewalPrice,
        @JsonProperty("currency_code")      String currencyCode,
        @JsonProperty("throttle_speed_kbps") Integer throttleSpeedKbps,
        @JsonProperty("rating_group")       Integer ratingGroup,
        @JsonProperty("priority")           Integer priority
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UsageRecord(
        @JsonProperty("service_type")    String serviceType,
        @JsonProperty("rating_group")    Integer ratingGroup,
        @JsonProperty("used_units")      Long usedUnits,
        @JsonProperty("unit_type")       String unitType,
        @JsonProperty("from_timestamp")  String fromTimestamp,
        @JsonProperty("to_timestamp")    String toTimestamp,
        @JsonProperty("charged_amount")  Long chargedAmount,
        @JsonProperty("currency_code")   String currencyCode
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ServiceEntitlement(
        @JsonProperty("service_id")          String serviceId,
        @JsonProperty("service_name")        String serviceName,
        @JsonProperty("enabled")             Boolean enabled,
        @JsonProperty("provisioned_date")    String provisionedDate,
        @JsonProperty("restriction_class")   String restrictionClass
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ChargingInfo(
        @JsonProperty("charging_rule_name")     String chargingRuleName,
        @JsonProperty("rating_group")           Integer ratingGroup,
        @JsonProperty("service_identifier")     Integer serviceIdentifier,
        @JsonProperty("online_charging")        Boolean onlineCharging,
        @JsonProperty("offline_charging")       Boolean offlineCharging,
        @JsonProperty("metering_method")        String meteringMethod,
        @JsonProperty("reporting_level")        String reportingLevel,
        @JsonProperty("result_code")            Integer resultCode
    ) {}

    // ── Factory methods ─────────────────────────────────────────────────────

    public static AccountInfoResponse success(String transactionId, String msisdn,
                                              String imsi, String timestamp,
                                              List<BundleInfo> bundles,
                                              List<UsageRecord> usageRecords,
                                              List<ServiceEntitlement> entitlements) {
        return new AccountInfoResponse(
            transactionId, "SUCCESS", "Account information retrieved successfully", timestamp,
            msisdn, imsi,
            "ACC-" + msisdn.replaceAll("[^0-9]", ""),
            "PREPAID", "PLAN_DATA_UNLIMITED", "Unlimited Data Plan",
            "ACTIVE", "EN", "2023-01-15T00:00:00Z", "2026-12-31T23:59:59Z",
            new BalanceInfo(25000L, "GBP", "2026-12-31T23:59:59Z"),
            new BalanceInfo(5000L, "GBP", "2026-09-30T23:59:59Z"),
            null,
            1_073_741_824L, 3600, "TERMINATE",
            bundles, usageRecords, entitlements,
            new ChargingInfo("CCR_DATA_RULE", 1, 1000, true, false, "DURATION_VOLUME", "SERVICE_IDENTIFIER_LEVEL", 2001),
            "23410", "23410", false,
            null
        );
    }

    public static AccountInfoResponse notFound(String transactionId, String msisdn) {
        return new AccountInfoResponse(
            transactionId, "SUBSCRIBER_NOT_FOUND", "No subscriber found for MSISDN", null,
            msisdn, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null,
            "No active subscription found for " + msisdn
        );
    }
}
