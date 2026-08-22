package com.telecombridge.quarkus.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * REST response payload returned from {@code POST /api/v1/charge}.
 *
 * <p>Matches the JSON shape of the Spring Boot gateway for API compatibility.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChargeResponse(

    @JsonProperty("session_id")
    String sessionId,

    @JsonProperty("result_code")
    int resultCode,

    @JsonProperty("result_message")
    String resultMessage,

    @JsonProperty("granted_units")
    Long grantedUnits,

    @JsonProperty("msisdn")
    String msisdn,

    @JsonProperty("service_id")
    String serviceId,

    @JsonProperty("error")
    String error

) {
    // ─── Factory helpers ────────────────────────────────────────────────────

    public static ChargeResponse success(String sessionId, int resultCode,
                                         long grantedUnits, String msisdn,
                                         String serviceId) {
        return new ChargeResponse(sessionId, resultCode, "Success",
                grantedUnits, msisdn, serviceId, null);
    }

    public static ChargeResponse failure(String sessionId, int resultCode,
                                          String message, String msisdn,
                                          String serviceId) {
        return new ChargeResponse(sessionId, resultCode, message,
                null, msisdn, serviceId, null);
    }

    public static ChargeResponse error(String errorMessage) {
        return new ChargeResponse(null, 0, null, null, null, null, errorMessage);
    }
}
