package com.telecombridge.quarkus.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Inbound request for {@code POST /in/get-account-info}.
 *
 * <p>Modelled from PCAP frame 10/25 — request payload ~1,629 bytes.
 * Carries subscriber identity + service context for an OCS account lookup.
 */
public record AccountInfoRequest(

    /** E.164 MSISDN of the subscriber. */
    @NotBlank
    @Pattern(regexp = "^\\+?[1-9]\\d{6,14}$", message = "Invalid MSISDN")
    @JsonProperty("msisdn")
    String msisdn,

    /** IMSI (15-digit) for HLR correlation. */
    @JsonProperty("imsi")
    String imsi,

    /** Unique transaction/correlation ID set by the caller. */
    @NotBlank
    @JsonProperty("transaction_id")
    String transactionId,

    /** Service type requested: DATA, VOICE, SMS, ROAMING, etc. */
    @JsonProperty("service_type")
    String serviceType,

    /** Network node ID making the request (SGSN/PGW/SMF identity). */
    @JsonProperty("node_id")
    String nodeId,

    /** Optional PLMN ID for roaming scenarios. */
    @JsonProperty("plmn_id")
    String plmnId,

    /** Requested service units (octets or seconds). */
    @JsonProperty("requested_units")
    Long requestedUnits,

    /** Rating group (maps to pricing plan). */
    @JsonProperty("rating_group")
    Integer ratingGroup,

    /** Service-Id (supplementary service identifier). */
    @JsonProperty("service_id")
    String serviceId,

    /** IP address allocated to subscriber (for data sessions). */
    @JsonProperty("ue_ip_address")
    String ueIpAddress,

    /** Access Point Name. */
    @JsonProperty("apn")
    String apn,

    /** Charging characteristics from subscription. */
    @JsonProperty("charging_characteristics")
    String chargingCharacteristics
) {}
