package com.telecombridge.quarkus.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * REST request payload for {@code POST /api/v1/charge}.
 *
 * <p>Mirrors the Spring Boot {@code ChargeRequest} record, using
 * Quarkus/Jakarta validation annotations.
 */
public record ChargeRequest(

    @NotBlank(message = "msisdn is required")
    @Pattern(regexp = "^\\+?[1-9]\\d{6,14}$", message = "Invalid MSISDN format")
    @JsonProperty("msisdn")
    String msisdn,

    @NotBlank(message = "service_id is required")
    @JsonProperty("service_id")
    String serviceId,

    @Min(value = 1, message = "requested_units must be at least 1")
    @JsonProperty("requested_units")
    long requestedUnits,

    @JsonProperty("session_id")
    String sessionId  // optional — server-generated if absent

) {
    /**
     * Returns the effective session ID: the caller-provided value if present,
     * otherwise a server-generated one based on MSISDN + timestamp.
     */
    public String effectiveSessionId() {
        if (sessionId != null && !sessionId.isBlank()) return sessionId;
        return msisdn.replaceAll("[^0-9]", "") + "-" + System.currentTimeMillis();
    }
}
