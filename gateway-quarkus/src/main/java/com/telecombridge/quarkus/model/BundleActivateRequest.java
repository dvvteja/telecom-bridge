package com.telecombridge.quarkus.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Inbound request for {@code POST /in/bundle-activate}.
 *
 * <p>Modelled from PCAP frame 73 — request payload ~1,650 bytes.
 * Carries subscriber identity + bundle selection for OCS-driven prepaid activation.
 */
public record BundleActivateRequest(

    /** E.164 MSISDN of the subscriber. */
    @NotBlank
    @Pattern(regexp = "^\\+?[1-9]\\d{6,14}$", message = "Invalid MSISDN")
    @JsonProperty("msisdn")
    String msisdn,

    /** IMSI for subscription lookup. */
    @JsonProperty("imsi")
    String imsi,

    /** Unique transaction/correlation ID set by the caller. */
    @NotBlank
    @JsonProperty("transaction_id")
    String transactionId,

    /** Bundle product code to activate (e.g., "DATA_1GB_30D"). */
    @NotBlank
    @JsonProperty("bundle_id")
    String bundleId,

    /**
     * Activation mode:
     * IMMEDIATE = activate now,
     * DEFERRED  = activate at next renewal,
     * AUTO_RENEW = recurring.
     */
    @JsonProperty("activation_mode")
    String activationMode,

    /** Channel through which activation was requested (USSD, APP, WEB, IVR). */
    @JsonProperty("channel")
    String channel,

    /** Optional promo code for discounted activation. */
    @JsonProperty("promo_code")
    String promoCode,

    /** Network node ID making the request. */
    @JsonProperty("node_id")
    String nodeId,

    /** PLMN ID (for roaming/MVNO contexts). */
    @JsonProperty("plmn_id")
    String plmnId,

    /** Current account balance (pre-deduction) in minor currency units. */
    @JsonProperty("account_balance")
    Long accountBalance,

    /** Currency code (ISO 4217: GBP, USD, EUR, INR…). */
    @JsonProperty("currency_code")
    String currencyCode,

    /** Subscriber's current tariff plan code. */
    @JsonProperty("tariff_plan")
    String tariffPlan,

    /** Existing active bundle IDs (for conflict checking). */
    @JsonProperty("active_bundle_ids")
    java.util.List<String> activeBundleIds
) {}
