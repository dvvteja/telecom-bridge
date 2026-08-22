package com.telecombridge.quarkus.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Response for {@code POST /in/bundle-activate}.
 *
 * <p>Modelled to produce ~2,800–2,900 bytes of JSON — matching the
 * 4-segment reassembled TCP payload seen in PCAP frames 79/85.
 *
 * <p>Contains activation result, effective dates, charges applied,
 * updated balance, and new bundle quota grant.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BundleActivateResponse(

    // ── Correlation ───────────────────────────────────────────────────────
    @JsonProperty("transaction_id")       String transactionId,
    @JsonProperty("result_code")          String resultCode,
    @JsonProperty("result_description")   String resultDescription,
    @JsonProperty("timestamp")            String timestamp,

    // ── Subscriber ───────────────────────────────────────────────────────
    @JsonProperty("msisdn")               String msisdn,
    @JsonProperty("imsi")                 String imsi,
    @JsonProperty("account_id")           String accountId,

    // ── Bundle Activation Details ─────────────────────────────────────────
    @JsonProperty("bundle_id")            String bundleId,
    @JsonProperty("bundle_name")          String bundleName,
    @JsonProperty("bundle_type")          String bundleType,
    @JsonProperty("activation_mode")      String activationMode,
    @JsonProperty("activation_date")      String activationDate,
    @JsonProperty("expiry_date")          String expiryDate,
    @JsonProperty("next_renewal_date")    String nextRenewalDate,
    @JsonProperty("auto_renewal_enabled") Boolean autoRenewalEnabled,

    // ── Quota Grant ───────────────────────────────────────────────────────
    @JsonProperty("total_quota")          Long totalQuota,        // octets or seconds
    @JsonProperty("quota_unit")           String quotaUnit,       // OCTETS / SECONDS
    @JsonProperty("throttle_speed_kbps")  Integer throttleSpeedKbps,
    @JsonProperty("validity_seconds")     Integer validitySeconds,

    // ── Charging ─────────────────────────────────────────────────────────
    @JsonProperty("price_charged")        Long priceCharged,      // minor currency units
    @JsonProperty("currency_code")        String currencyCode,
    @JsonProperty("balance_before")       Long balanceBefore,
    @JsonProperty("balance_after")        Long balanceAfter,
    @JsonProperty("discount_applied")     Long discountApplied,
    @JsonProperty("promo_code_used")      String promoCodeUsed,
    @JsonProperty("tax_amount")           Long taxAmount,
    @JsonProperty("charging_reference")   String chargingReference,

    // ── Updated Active Bundles ────────────────────────────────────────────
    @JsonProperty("active_bundles")       List<ActiveBundleSummary> activeBundles,

    // ── Notifications ─────────────────────────────────────────────────────
    @JsonProperty("sms_notification")     String smsNotification,
    @JsonProperty("push_notification")    String pushNotification,

    // ── Error ─────────────────────────────────────────────────────────────
    @JsonProperty("error_detail")         String errorDetail

) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ActiveBundleSummary(
        @JsonProperty("bundle_id")       String bundleId,
        @JsonProperty("bundle_name")     String bundleName,
        @JsonProperty("bundle_type")     String bundleType,
        @JsonProperty("remaining_quota") Long remainingQuota,
        @JsonProperty("quota_unit")      String quotaUnit,
        @JsonProperty("expiry_date")     String expiryDate,
        @JsonProperty("priority")        Integer priority
    ) {}

    // ── Factory methods ─────────────────────────────────────────────────────

    public static BundleActivateResponse success(String transactionId, String msisdn,
                                                  String imsi, String bundleId,
                                                  String bundleName, String timestamp,
                                                  long price, long balanceBefore) {
        long balanceAfter = balanceBefore - price;
        return new BundleActivateResponse(
            transactionId, "SUCCESS", "Bundle activated successfully", timestamp,
            msisdn, imsi, "ACC-" + msisdn.replaceAll("[^0-9]", ""),
            bundleId, bundleName, "DATA", "IMMEDIATE",
            timestamp,
            "2026-09-21T23:59:59Z",
            "2026-09-21T23:59:59Z",
            true,
            1_073_741_824L, "OCTETS", null, 2592000,
            price, "GBP", balanceBefore, balanceAfter, 0L, null, 0L,
            "CHG-" + System.currentTimeMillis(),
            List.of(
                new ActiveBundleSummary(bundleId, bundleName, "DATA",
                    1_073_741_824L, "OCTETS", "2026-09-21T23:59:59Z", 1),
                new ActiveBundleSummary("BUNDLE_VOICE_200MIN", "200 Minutes Bundle", "VOICE",
                    7_200L, "SECONDS", "2026-09-15T23:59:59Z", 2)
            ),
            "You have successfully activated " + bundleName + ". Valid until 21 Sep 2026.",
            null, null
        );
    }

    public static BundleActivateResponse insufficientBalance(String transactionId,
                                                              String msisdn,
                                                              long required,
                                                              long available) {
        return new BundleActivateResponse(
            transactionId, "INSUFFICIENT_BALANCE",
            "Account balance insufficient for bundle activation", null,
            msisdn, null, null, null, null, null, null, null, null, null, null,
            null, null, null, null,
            required, "GBP", available, available, null, null, null, null,
            null, null, null,
            "Required " + required + " minor units, available " + available
        );
    }
}
