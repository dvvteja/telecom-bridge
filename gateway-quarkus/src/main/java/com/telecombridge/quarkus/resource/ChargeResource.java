package com.telecombridge.quarkus.resource;

import com.telecombridge.common.diameter.AvpCode;
import com.telecombridge.quarkus.diameter.DiameterConfig;
import com.telecombridge.quarkus.diameter.JDiameterStackManager;
import com.telecombridge.quarkus.model.ChargeRequest;
import com.telecombridge.quarkus.model.ChargeResponse;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jdiameter.api.Answer;
import org.jdiameter.api.Avp;
import org.jdiameter.api.AvpDataException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeoutException;

/**
 * JAX-RS resource exposing the {@code POST /api/v1/charge} endpoint and
 * the {@code GET /api/v1/health/diameter} status endpoint.
 *
 * <h3>Async model</h3>
 * The method returns a {@link CompletionStage}. Quarkus RESTEasy Reactive
 * handles this natively — no HTTP thread is blocked while awaiting the CCA
 * from the Diameter stack. The future is completed on the JDiameter event-loop
 * thread when the CCA arrives.
 *
 * <h3>Error mapping</h3>
 * <ul>
 *   <li>Diameter unavailable → 503 Service Unavailable</li>
 *   <li>CCA not received within timeout → 504 Gateway Timeout</li>
 *   <li>Non-2001 Result-Code → 402 Payment Required</li>
 *   <li>Validation failure → 400 Bad Request (handled by ExceptionMapper)</li>
 * </ul>
 */
@Path("/api/v1")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ChargeResource {

    private static final Logger log = LoggerFactory.getLogger(ChargeResource.class);

    @Inject
    JDiameterStackManager diameterStack;

    @Inject
    DiameterConfig config;

    // ─── POST /api/v1/charge ──────────────────────────────────────────────

    /**
     * Initiates a Credit-Control transaction (CCR/CCA) for the given subscriber.
     *
     * @param request JSON payload with MSISDN, service ID, and requested units
     * @return async response with the CCA result or an error status
     */
    @POST
    @Path("/charge")
    public CompletionStage<Response> charge(@Valid ChargeRequest request) {
        String sessionId = request.effectiveSessionId();
        log.info("Charge request: sessionId={} MSISDN={} service={} units={}",
                sessionId, request.msisdn(), request.serviceId(), request.requestedUnits());

        // Guard: fail fast if stack not connected
        if (!diameterStack.isConnected()) {
            log.warn("Diameter stack unavailable for sessionId={}", sessionId);
            return CompletableFuture.completedFuture(
                Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(ChargeResponse.error("Diameter stack is currently unavailable. Please retry."))
                    .build());
        }

        // Send CCR asynchronously — returns immediately
        return diameterStack.sendCcr(request.msisdn(), request.requestedUnits())
            .thenApply(cca -> mapCcaToResponse(request, sessionId, cca))
            .exceptionally(ex -> mapExceptionToResponse(request, sessionId, ex));
    }

    // ─── GET /api/v1/health/diameter ─────────────────────────────────────

    /**
     * Returns the Diameter connection status (compatible with Spring Boot gateway shape).
     */
    @GET
    @Path("/health/diameter")
    public Response diameterHealth() {
        if (diameterStack.isConnected()) {
            return Response.ok(new java.util.LinkedHashMap<>() {{
                put("status", "UP");
                put("pendingRequests", diameterStack.getPendingCount());
                put("peer", config.serverHost() + ":" + config.serverPort());
                put("runtime", "Quarkus+JDiameter");
            }}).build();
        }
        return Response.status(Response.Status.SERVICE_UNAVAILABLE)
            .entity(java.util.Map.of(
                "status", "DOWN",
                "error", diameterStack.getErrorMessage()))
            .build();
    }

    // ─── Private mapping helpers ──────────────────────────────────────────

    private Response mapCcaToResponse(ChargeRequest request, String sessionId, Answer cca) {
        try {
            Avp resultCodeAvp = cca.getAvps().getAvp(AvpCode.RESULT_CODE);
            long resultCode   = resultCodeAvp != null ? resultCodeAvp.getUnsigned32() : -1L;

            if (resultCode == AvpCode.RESULT_CODE_SUCCESS) {
                long grantedUnits = extractGrantedOctets(cca);
                log.info("Charge success: sessionId={} MSISDN={} grantedOctets={}",
                         sessionId, request.msisdn(), grantedUnits);

                return Response.ok(ChargeResponse.success(
                    sessionId, (int) resultCode, grantedUnits,
                    request.msisdn(), request.serviceId())).build();
            } else {
                log.warn("Charge denied: sessionId={} MSISDN={} resultCode={}",
                         sessionId, request.msisdn(), resultCode);
                return Response.status(402)
                    .entity(ChargeResponse.failure(
                        sessionId, (int) resultCode,
                        "Credit control denied (Diameter Result-Code: " + resultCode + ")",
                        request.msisdn(), request.serviceId()))
                    .build();
            }
        } catch (AvpDataException e) {
            log.error("Failed to parse CCA AVPs for sessionId={}: {}", sessionId, e.getMessage(), e);
            return Response.serverError()
                .entity(ChargeResponse.error("Failed to parse Diameter response: " + e.getMessage()))
                .build();
        }
    }

    private Response mapExceptionToResponse(ChargeRequest request, String sessionId, Throwable ex) {
        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;

        if (cause instanceof TimeoutException) {
            log.error("CCA timeout: sessionId={} MSISDN={} after {}ms",
                      sessionId, request.msisdn(), config.requestTimeoutMs());
            return Response.status(504)
                .entity(ChargeResponse.error(
                    "Diameter server did not respond within " + config.requestTimeoutMs() + "ms"))
                .build();
        }

        if (cause instanceof IllegalStateException) {
            log.error("Diameter unavailable for sessionId={}: {}", sessionId, cause.getMessage());
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .entity(ChargeResponse.error(cause.getMessage()))
                .build();
        }

        log.error("Unexpected error for sessionId={}: {}", sessionId, cause.getMessage(), cause);
        return Response.serverError()
            .entity(ChargeResponse.error("Internal error: " + cause.getMessage()))
            .build();
    }

    /**
     * Extracts CC-Total-Octets from the Granted-Service-Unit grouped AVP in a CCA.
     *
     * <p>JDiameter exposes nested AVPs via {@link Avp#getGrouped()}, making this
     * cleaner than the raw-byte walking required by the Netty approach.
     */
    private long extractGrantedOctets(Answer cca) throws AvpDataException {
        Avp gsuAvp = cca.getAvps().getAvp(AvpCode.GRANTED_SERVICE_UNIT);
        if (gsuAvp == null) return 0L;

        // Drill into Granted-Service-Unit grouped AVP
        for (Avp child : gsuAvp.getGrouped()) {
            if (child.getCode() == AvpCode.CC_TOTAL_OCTETS) {
                return child.getUnsigned64();
            }
        }
        return 0L;
    }
}
