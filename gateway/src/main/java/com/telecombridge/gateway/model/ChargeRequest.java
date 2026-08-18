package com.telecombridge.gateway.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * REST request payload for the {@code POST /api/v1/charge} endpoint.
 */
public record ChargeRequest(

    @NotBlank(message = "msisdn is required")
    @Pattern(regexp = "^\\+?[1-9]\\d{6,14}$", message = "Invalid MSISDN format")
    @JsonProperty("msisdn")
    String msisdn,

    @NotBlank(message = "serviceId is required")
    @JsonProperty("service_id")
    String serviceId,

    @Min(value = 1, message = "requestedUnits must be at least 1")
    @JsonProperty("requested_units")
    long requestedUnits,

    @JsonProperty("session_id")
    String sessionId  // optional — generated server-side if absent
) {
    /** Returns a session ID, generating one from MSISDN + timestamp if not provided. */
    public String effectiveSessionId() {
        if (sessionId != null && !sessionId.isBlank()) return sessionId;
        return msisdn.replaceAll("[^0-9]", "") + "-" + System.currentTimeMillis();
    }
}
