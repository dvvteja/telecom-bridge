package com.telecombridge.quarkus.resource;

import com.telecombridge.quarkus.model.AccountInfoRequest;
import com.telecombridge.quarkus.model.AccountInfoResponse;
import com.telecombridge.quarkus.model.BundleActivateRequest;
import com.telecombridge.quarkus.model.BundleActivateResponse;
import com.telecombridge.quarkus.service.TelecomApiSimulatorService;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JAX-RS resource recreating the telecom northbound HTTP/JSON API
 * observed in the PCAP capture.
 *
 * <h3>PCAP-Derived Endpoints</h3>
 * <pre>
 *   Frame 10/25/55: POST /in/get-account-info  → ~5,100 byte JSON  (200 OK, ~1s delay)
 *   Frame 73:       POST /in/bundle-activate   → ~2,800 byte JSON  (200 OK, ~1s delay)
 * </pre>
 *
 * <h3>Network Characteristics Replicated</h3>
 * <ul>
 *   <li>~1,005ms average backend processing delay (BASE_DELAY ± 50ms jitter)</li>
 *   <li>Chunked transfer encoding for responses >4KB (Quarkus default)</li>
 *   <li>Supports 2+ concurrent persistent HTTP/1.1 connections (PCAP: ports 50008 + 19924)</li>
 *   <li>JSON response size: account-info ~5,100 bytes, bundle-activate ~2,800 bytes</li>
 * </ul>
 *
 * <h3>Port</h3>
 * Binds on port 31021 (matching the PCAP destination) via
 * {@code quarkus.http.port=31021} in test profile, or standard 8082 in Docker.
 */
@Path("/in")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class TelecomApiResource {

    private static final Logger log = LoggerFactory.getLogger(TelecomApiResource.class);

    @Inject
    TelecomApiSimulatorService simulatorService;

    // ─── POST /in/get-account-info ────────────────────────────────────────

    /**
     * Retrieves full subscriber account information from the OCS.
     *
     * <p>PCAP evidence:
     * <ul>
     *   <li>Frame 10 (t=0.001s): POST 1,629 bytes from 10.44.24.6:50008</li>
     *   <li>Frame 37 (t=1.008s): HTTP/1.1 200, 4-segment reassembled ~5,128 bytes</li>
     *   <li>Frame 25 (t=0.072s): Concurrent POST from 10.44.24.6:19924</li>
     *   <li>Frame 49 (t=1.076s): HTTP/1.1 200 response for concurrent call</li>
     * </ul>
     *
     * @param request Subscriber identity + service context (~1,629 byte JSON body)
     * @return Full account state (~5,100 byte JSON response) after ~1s processing
     */
    @POST
    @Path("/get-account-info")
    public Uni<Response> getAccountInfo(@Valid AccountInfoRequest request) {
        log.debug("POST /in/get-account-info: MSISDN={} txId={}",
                  request.msisdn(), request.transactionId());

        return simulatorService.getAccountInfo(request)
            .map(accountInfo -> {
                if ("SUCCESS".equals(accountInfo.resultCode())) {
                    return Response.ok(accountInfo).build();
                } else if ("SUBSCRIBER_NOT_FOUND".equals(accountInfo.resultCode())) {
                    return Response.status(Response.Status.NOT_FOUND)
                                   .entity(accountInfo).build();
                }
                return Response.serverError().entity(accountInfo).build();
            });
    }

    // ─── POST /in/bundle-activate ─────────────────────────────────────────

    /**
     * Activates a prepaid bundle for a subscriber.
     *
     * <p>PCAP evidence:
     * <ul>
     *   <li>Frame 73 (t=16.681s): POST 1,650 bytes from 10.44.24.6:19924</li>
     *   <li>Frame 85 (t=17.686s): HTTP/1.1 200, 4-segment reassembled ~2,831 bytes</li>
     * </ul>
     *
     * @param request Bundle activation request with subscriber identity + bundle ID (~1,650 bytes)
     * @return Activation result with quota grant + updated balance (~2,800 byte JSON)
     */
    @POST
    @Path("/bundle-activate")
    public Uni<Response> bundleActivate(@Valid BundleActivateRequest request) {
        log.debug("POST /in/bundle-activate: MSISDN={} bundle={} txId={}",
                  request.msisdn(), request.bundleId(), request.transactionId());

        return simulatorService.bundleActivate(request)
            .map(result -> {
                if ("SUCCESS".equals(result.resultCode())) {
                    return Response.ok(result).build();
                } else if ("INSUFFICIENT_BALANCE".equals(result.resultCode())) {
                    // HTTP 402 Payment Required — consistent with ChargeResource convention
                    return Response.status(402).entity(result).build();
                } else if ("SUBSCRIBER_NOT_FOUND".equals(result.resultCode())) {
                    return Response.status(Response.Status.NOT_FOUND).entity(result).build();
                }
                return Response.serverError().entity(result).build();
            });
    }

    // ─── GET /in/health ───────────────────────────────────────────────────

    /**
     * Simple liveness endpoint for the telecom API path prefix.
     * Useful for load balancer health checks targeting port 31021.
     */
    @GET
    @Path("/health")
    public Response health() {
        return Response.ok(java.util.Map.of(
            "status", "UP",
            "service", "telecom-api",
            "endpoints", java.util.List.of(
                "POST /in/get-account-info",
                "POST /in/bundle-activate"
            )
        )).build();
    }
}
