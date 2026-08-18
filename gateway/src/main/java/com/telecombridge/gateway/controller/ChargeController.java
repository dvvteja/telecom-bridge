package com.telecombridge.gateway.controller;

import com.telecombridge.common.diameter.Avp;
import com.telecombridge.common.diameter.AvpCode;
import com.telecombridge.common.diameter.DiameterCodec;
import com.telecombridge.common.diameter.DiameterMessage;
import com.telecombridge.gateway.diameter.DiameterClient;
import com.telecombridge.gateway.diameter.DiameterClientConfig;
import com.telecombridge.gateway.model.ChargeRequest;
import com.telecombridge.gateway.model.ChargeResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

/**
 * REST controller exposing the {@code POST /api/v1/charge} endpoint.
 *
 * <h3>Async model</h3>
 * The method returns a {@link CompletableFuture}. Spring MVC detects this and
 * uses an {@link org.springframework.web.context.request.async.DeferredResult}
 * internally, releasing the HTTP thread immediately. The future is completed
 * by the Netty I/O thread when the CCA arrives from the Diameter server.
 *
 * <h3>Error handling</h3>
 * <ul>
 *   <li>Simulator unavailable → 503 Service Unavailable</li>
 *   <li>No CCA within timeout → 504 Gateway Timeout</li>
 *   <li>Non-2001 Result-Code → 402 Payment Required</li>
 *   <li>Validation failure → 400 Bad Request (handled by {@link GlobalExceptionHandler})</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1")
public class ChargeController {

    private static final Logger log = LoggerFactory.getLogger(ChargeController.class);

    private final DiameterClient       diameterClient;
    private final DiameterClientConfig config;

    public ChargeController(DiameterClient diameterClient, DiameterClientConfig config) {
        this.diameterClient = diameterClient;
        this.config         = config;
    }

    /**
     * Initiates a Credit-Control transaction for the given subscriber.
     *
     * @param request JSON payload with MSISDN, service ID, and requested units
     * @return HTTP 200 + CCA result on success; error responses on failure
     */
    @PostMapping("/charge")
    public CompletableFuture<ResponseEntity<ChargeResponse>> charge(
            @Valid @RequestBody ChargeRequest request) {

        String sessionId = request.effectiveSessionId();
        log.info("Charge request: sessionId={} MSISDN={} serviceId={} units={}",
                sessionId, request.msisdn(), request.serviceId(), request.requestedUnits());

        // Check connectivity before sending
        if (!diameterClient.isConnected()) {
            log.warn("Diameter server unavailable for sessionId={}", sessionId);
            return CompletableFuture.completedFuture(
                    ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                            .body(ChargeResponse.error(
                                    "Diameter server is currently unavailable. Please retry.")));
        }

        // Send CCR asynchronously — this returns immediately
        CompletableFuture<DiameterMessage> ccaFuture =
                diameterClient.sendCcr(request.msisdn(), request.requestedUnits());

        // Transform the Diameter CCA to a REST response (on the Netty thread, non-blocking)
        return ccaFuture.thenApply(cca -> mapCcaToResponse(request, sessionId, cca))
                        .exceptionally(ex -> mapExceptionToResponse(request, sessionId, ex));
    }

    // ─── Health endpoint ──────────────────────────────────────────────────

    @GetMapping("/health/diameter")
    public ResponseEntity<Object> diameterHealth() {
        if (diameterClient.isConnected()) {
            return ResponseEntity.ok(new java.util.LinkedHashMap<>() {{
                put("status", "UP");
                put("pendingRequests", diameterClient.getPendingRequestCount());
                put("server", config.getServerHost() + ":" + config.getServerPort());
            }});
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(java.util.Map.of("status", "DOWN"));
    }

    // ─── Private mapping helpers ──────────────────────────────────────────

    private ResponseEntity<ChargeResponse> mapCcaToResponse(
            ChargeRequest request, String sessionId, DiameterMessage cca) {

        var resultCodeAvp  = cca.findAvp(AvpCode.RESULT_CODE);
        var grantedUnitAvp = cca.findAvp(AvpCode.GRANTED_SERVICE_UNIT);

        long resultCode = resultCodeAvp != null
                ? DiameterCodec.decodeUint32(resultCodeAvp.getData()) : -1;

        if (resultCode == AvpCode.RESULT_CODE_SUCCESS) {
            long grantedOctets = extractGrantedOctets(grantedUnitAvp);

            log.info("Charge success: sessionId={} MSISDN={} grantedOctets={}",
                    sessionId, request.msisdn(), grantedOctets);

            return ResponseEntity.ok(ChargeResponse.success(
                    sessionId, (int) resultCode, grantedOctets,
                    request.msisdn(), request.serviceId()));
        } else {
            log.warn("Charge denied: sessionId={} MSISDN={} resultCode={}",
                    sessionId, request.msisdn(), resultCode);
            return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                    .body(ChargeResponse.failure(
                            sessionId, (int) resultCode,
                            "Credit control denied (Diameter Result-Code: " + resultCode + ")",
                            request.msisdn(), request.serviceId()));
        }
    }

    private ResponseEntity<ChargeResponse> mapExceptionToResponse(
            ChargeRequest request, String sessionId, Throwable ex) {

        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;

        if (cause instanceof TimeoutException) {
            log.error("CCA timeout for sessionId={} MSISDN={} after {}ms",
                    sessionId, request.msisdn(), config.getRequestTimeoutMs());
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                    .body(ChargeResponse.error(
                            "Diameter server did not respond within "
                            + config.getRequestTimeoutMs() + "ms"));
        }

        if (cause instanceof IllegalStateException) {
            log.error("Diameter unavailable for sessionId={}: {}", sessionId, cause.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ChargeResponse.error(cause.getMessage()));
        }

        log.error("Unexpected error for sessionId={}: {}", sessionId, cause.getMessage(), cause);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ChargeResponse.error("Internal error: " + cause.getMessage()));
    }

    /**
     * Extracts the CC-Total-Octets value from a Granted-Service-Unit grouped AVP.
     *
     * <p>The codec decodes AVPs arriving over the wire as flat byte arrays (leaf AVPs),
     * even when they are logically grouped. This helper handles both cases:
     * <ol>
     *   <li>Pre-built grouped AVP (e.g., from a local message factory) — uses {@code getGrouped()}</li>
     *   <li>Wire-decoded AVP (raw bytes) — re-parses the bytes to extract child AVPs</li>
     * </ol>
     */
    private long extractGrantedOctets(Avp grantedUnitAvp) {
        if (grantedUnitAvp == null) return 0L;

        // Case 1: in-memory grouped AVP (e.g., created by DiameterMessageFactory)
        if (grantedUnitAvp.isGrouped()) {
            for (Avp child : grantedUnitAvp.getGrouped()) {
                if (child.getCode() == AvpCode.CC_TOTAL_OCTETS && child.getData() != null
                        && child.getData().length >= 8) {
                    return DiameterCodec.decodeUint64(child.getData());
                }
            }
            return 0L;
        }

        // Case 2: wire-decoded AVP — raw bytes contain encoded child AVPs
        byte[] raw = grantedUnitAvp.getData();
        if (raw == null || raw.length < 8) return 0L;

        io.netty.buffer.ByteBuf buf = io.netty.buffer.Unpooled.wrappedBuffer(raw);
        try {
            while (buf.readableBytes() >= 8) {
                int    childCode   = buf.readInt();
                byte   childFlags  = buf.readByte();
                int    childLen    = ((buf.readByte() & 0xFF) << 16)
                                   | ((buf.readByte() & 0xFF) << 8)
                                   |  (buf.readByte() & 0xFF);
                boolean vendor     = (childFlags & Avp.FLAG_VENDOR) != 0;
                int    headerLen   = vendor ? 12 : 8;
                if (vendor && buf.readableBytes() >= 4) buf.skipBytes(4);

                int dataLen = childLen - headerLen;
                if (dataLen < 0 || buf.readableBytes() < dataLen) break;

                byte[] childData = new byte[dataLen];
                buf.readBytes(childData);

                if (childCode == AvpCode.CC_TOTAL_OCTETS && childData.length >= 8) {
                    return DiameterCodec.decodeUint64(childData);
                }

                // Skip 4-byte alignment padding
                int pad = (4 - (childLen % 4)) % 4;
                if (pad > 0 && buf.readableBytes() >= pad) buf.skipBytes(pad);
            }
        } finally {
            buf.release();
        }
        return 0L;
    }
}
