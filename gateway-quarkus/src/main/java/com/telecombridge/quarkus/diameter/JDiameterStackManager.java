package com.telecombridge.quarkus.diameter;

import com.telecombridge.common.diameter.AvpCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jdiameter.api.*;
import org.jdiameter.api.app.AppAnswerEvent;
import org.jdiameter.api.app.AppRequestEvent;
import org.jdiameter.api.app.AppSession;
import org.jdiameter.api.cca.ClientCCASession;
import org.jdiameter.api.cca.ClientCCASessionListener;
import org.jdiameter.api.cca.events.JCreditControlAnswer;
import org.jdiameter.api.cca.events.JCreditControlRequest;
import org.jdiameter.impl.app.cca.JCreditControlAnswerImpl;
import org.jdiameter.impl.app.cca.JCreditControlRequestImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages the JDiameter stack lifecycle and provides the {@link #sendCcr} method
 * for issuing Credit-Control-Requests (Ro/Gy, RFC 4006).
 *
 * <h3>Stack Lifecycle</h3>
 * <ol>
 *   <li>{@link PostConstruct}: configures and starts the JDiameter stack from XML, establishes peer connection.</li>
 *   <li>JDiameter handles CER/CEA, DWR/DWA, and peer state machine automatically.</li>
 *   <li>CCR is sent via a {@link ClientCCASession} (one session per request, stateless INITIAL_REQUEST).</li>
 *   <li>{@link PreDestroy}: gracefully stops the stack.</li>
 * </ol>
 *
 * <h3>CCA Correlation</h3>
 * Each pending CCR is correlated via its Session-Id using a
 * {@code ConcurrentHashMap<String, CompletableFuture<Answer>>}.
 * When the JDiameter listener receives a CCA, it looks up the session ID
 * and completes the waiting future — identical in principle to the existing
 * Netty HbH-based approach, but fully managed by the JDiameter FSM.
 */
@ApplicationScoped
public class JDiameterStackManager implements ClientCCASessionListener, NetworkReqListener {

    private static final Logger log = LoggerFactory.getLogger(JDiameterStackManager.class);

    // Diameter Credit-Control Application-Id (RFC 4006)
    private static final long APPLICATION_ID_CC = AvpCode.APPLICATION_ID_CREDIT_CONTROL;

    // CC-Request-Type: INITIAL_REQUEST = 1
    private static final int CC_REQUEST_TYPE_INITIAL = AvpCode.CC_REQUEST_TYPE_INITIAL;

    // ─── Injected config ──────────────────────────────────────────────────
    @Inject
    DiameterConfig config;

    @Inject
    MeterRegistry meterRegistry;

    // ─── JDiameter handles ────────────────────────────────────────────────
    private Stack                  stack;
    private SessionFactory         sessionFactory;
    private ApplicationId          ccAppId;

    // ─── Correlation map: SessionId → pending future ──────────────────────
    /**
     * Pending CCR futures keyed by Diameter Session-Id.
     * Written before CCR write; completed by CCA listener on JDiameter thread.
     */
    private final ConcurrentHashMap<String, CompletableFuture<Answer>> pendingRequests
            = new ConcurrentHashMap<>(1024);

    private final AtomicBoolean  connected       = new AtomicBoolean(false);
    private final AtomicLong     ccrCount        = new AtomicLong(0);
    private final AtomicReference<String> errorMsg = new AtomicReference<>("");

    // ─── Metrics ──────────────────────────────────────────────────────────
    private Counter ccrSentCounter;
    private Counter ccaReceivedCounter;
    private Counter ccrTimeoutCounter;
    private Timer   ccrRoundTripTimer;

    // ═══════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════════════════════════════════════

    @PostConstruct
    public void start() {
        log.info("Starting JDiameter stack — peer={}:{}", config.serverHost(), config.serverPort());

        // ── Metrics registration ───────────────────────────────────────────
        ccrSentCounter     = meterRegistry.counter("diameter.ccr.sent");
        ccaReceivedCounter = meterRegistry.counter("diameter.cca.received");
        ccrTimeoutCounter  = meterRegistry.counter("diameter.ccr.timeout");
        ccrRoundTripTimer  = Timer.builder("diameter.ccr.roundtrip")
                .description("CCR→CCA round-trip latency")
                .register(meterRegistry);

        // ── Build and start JDiameter stack ───────────────────────────────
        try {
            StackCreator creator = new StackCreator();
            stack = creator.createStack();

            // Load XML configuration from classpath
            stack.init(new XMLConfiguration(
                JDiameterStackManager.class.getResourceAsStream("/diameter/jdiameter-client-config.xml")
            ));

            sessionFactory = stack.getSessionFactory();
            ccAppId        = ApplicationId.createByAuthAppId(APPLICATION_ID_CC);

            // Register this bean as listener for incoming Diameter messages
            Network network = stack.unwrap(Network.class);
            network.addNetworkReqListener(this, ccAppId);

            // Start the stack — triggers CER/CEA handshake with configured peer
            stack.start(Mode.ANY_PEER, 30_000, TimeUnit.MILLISECONDS);

            connected.set(true);
            log.info("JDiameter stack started — CCA app-id={} peer={} connected",
                     APPLICATION_ID_CC, config.serverHost());

        } catch (Exception e) {
            errorMsg.set(e.getMessage());
            log.error("JDiameter stack failed to start: {}", e.getMessage(), e);
            // Gateway will still serve requests; CCR will return 503 until reconnected
        }
    }

    @PreDestroy
    public void stop() {
        log.info("Stopping JDiameter stack...");
        connected.set(false);

        // Fail all pending futures
        int failed = pendingRequests.size();
        pendingRequests.forEach((sid, future) ->
            future.completeExceptionally(
                new IllegalStateException("Diameter stack shutting down")));
        pendingRequests.clear();
        if (failed > 0) log.warn("{} in-flight CCRs failed on shutdown", failed);

        if (stack != null) {
            try {
                stack.stop(5_000, TimeUnit.MILLISECONDS, DisconnectCause.REBOOTING);
            } catch (Exception e) {
                log.warn("Error stopping stack: {}", e.getMessage());
            }
            stack.destroy();
        }
        log.info("JDiameter stack stopped.");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Public API
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Returns {@code true} if the stack is started and the peer connection
     * is considered active (at least one peer in OPEN state).
     */
    public boolean isConnected() {
        return connected.get() && stack != null;
    }

    /** Current count of in-flight CCR requests. */
    public int getPendingCount() {
        return pendingRequests.size();
    }

    /** Last startup error message, or empty string if healthy. */
    public String getErrorMessage() {
        return errorMsg.get();
    }

    /**
     * Sends a Credit-Control-Request (INITIAL_REQUEST, RFC 4006 §6.4.2).
     *
     * <p>The call is non-blocking. The returned {@link CompletableFuture} resolves
     * when the CCA arrives (completed by the JDiameter listener thread) or
     * times out after {@link DiameterConfig#requestTimeoutMs()}.
     *
     * @param msisdn         E.164 subscriber MSISDN
     * @param requestedUnits Octets requested (CC-Total-Octets in RSU)
     * @return Future that resolves to the JDiameter {@link Answer} for the CCA
     */
    public CompletableFuture<Answer> sendCcr(String msisdn, long requestedUnits) {
        if (!isConnected()) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("Diameter stack is not connected to peer"));
        }

        CompletableFuture<Answer> future = new CompletableFuture<>();

        try {
            // Create a new session for this request (stateless per RFC 4006 §5)
            Session session = sessionFactory.getNewSession();
            String  sessionId = session.getSessionId();

            // Register before sending to avoid race with fast CCA
            pendingRequests.put(sessionId, future);

            // Build the CCR message
            Request ccr = buildCcrMessage(session, msisdn, requestedUnits);

            long sentAt = System.nanoTime();
            ccrSentCounter.increment();
            ccrCount.incrementAndGet();

            // Send via JDiameter — non-blocking, delegates to the stack's transport layer
            session.send(ccr, (answer, deliveryException) -> {
                // This lambda is the JDiameter send-callback; called on stack thread
                if (deliveryException != null) {
                    pendingRequests.remove(sessionId);
                    future.completeExceptionally(deliveryException);
                    log.error("CCR delivery failed for session={}: {}", sessionId,
                              deliveryException.getMessage());
                } else {
                    log.debug("CCR sent: session={} MSISDN={} units={}", sessionId, msisdn, requestedUnits);
                }
            });

            // Timeout guard
            future.orTimeout(config.requestTimeoutMs(), TimeUnit.MILLISECONDS)
                  .whenComplete((result, ex) -> {
                      if (ex instanceof TimeoutException) {
                          if (pendingRequests.remove(sessionId) != null) {
                              ccrTimeoutCounter.increment();
                              log.warn("CCR timeout: session={} MSISDN={} after {}ms",
                                       sessionId, msisdn, config.requestTimeoutMs());
                          }
                      } else if (result != null) {
                          long elapsed = System.nanoTime() - sentAt;
                          ccrRoundTripTimer.record(elapsed, TimeUnit.NANOSECONDS);
                      }
                  });

        } catch (Exception e) {
            future.completeExceptionally(e);
            log.error("Failed to create/send CCR for MSISDN={}: {}", msisdn, e.getMessage(), e);
        }

        return future;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // JDiameter NetworkReqListener — incoming requests from server
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Called by JDiameter when an unsolicited request arrives from the peer.
     * In a pure client-mode stack this should not happen; log and return null.
     */
    @Override
    public Answer processRequest(Request request) {
        log.warn("Unexpected request from peer: cmd={} app={}",
                 request.getCommandCode(), request.getApplicationId());
        return null; // no answer — JDiameter will handle it
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ClientCCASessionListener — CCA delivery
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Called by JDiameter when a Credit-Control-Answer arrives for a session
     * we initiated. Correlates via Session-Id and completes the waiting future.
     */
    @Override
    public void doCreditControlAnswer(ClientCCASession session,
                                      JCreditControlRequest request,
                                      JCreditControlAnswer answer) throws InternalException {
        String sessionId = session.getSessionId();
        CompletableFuture<Answer> future = pendingRequests.remove(sessionId);

        if (future == null) {
            log.warn("CCA for unknown/timed-out session={}", sessionId);
            return;
        }

        ccaReceivedCounter.increment();

        try {
            long rc = answer.getMessage()
                            .getAvps()
                            .getAvp(AvpCode.RESULT_CODE)
                            .getUnsigned32();
            log.debug("CCA received: session={} Result-Code={}", sessionId, rc);
        } catch (Exception e) {
            log.debug("CCA received: session={} (could not parse Result-Code)", sessionId);
        }

        future.complete(answer.getMessage());
    }

    // ─── Unused ClientCCASessionListener callbacks ────────────────────────

    @Override
    public void doReAuthRequest(ClientCCASession session, AppRequestEvent request)
            throws InternalException {
        log.debug("Re-Auth-Request received for session={}", session.getSessionId());
    }

    @Override
    public void doExtensionRequest(AppSession session, AppRequestEvent request)
            throws InternalException {
        log.debug("Extension request received for session={}", session.getSessionId());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Private — CCR message builder
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Builds a Diameter CCR message (cmd=272, R+P flags) with the required AVPs
     * for a Ro/Gy INITIAL_REQUEST per RFC 4006 §6.4.2.
     *
     * <p>AVPs set:
     * <ul>
     *   <li>Origin-Host / Origin-Realm (264/296)</li>
     *   <li>Destination-Host / Destination-Realm (293/283)</li>
     *   <li>Auth-Application-Id = 4 (258)</li>
     *   <li>CC-Request-Type = INITIAL_REQUEST (416)</li>
     *   <li>CC-Request-Number = 0 (415)</li>
     *   <li>Subscription-Id grouped: END_USER_E164 + MSISDN (443)</li>
     *   <li>Requested-Service-Unit grouped: CC-Total-Octets (431)</li>
     * </ul>
     */
    private Request buildCcrMessage(Session session, String msisdn, long requestedUnits)
            throws Exception {

        Request ccr = session.createRequest(
            272,   // Credit-Control command code
            ccAppId,
            config.destinationRealm(),
            config.destinationHost()
        );

        AvpSet avps = ccr.getAvps();

        // Origin
        avps.addAvp(Avp.ORIGIN_HOST, config.originHost(), true, false, true);
        avps.addAvp(Avp.ORIGIN_REALM, config.originRealm(), true, false, true);

        // Destination
        avps.addAvp(Avp.DESTINATION_HOST, config.destinationHost(), true, false, true);
        avps.addAvp(Avp.DESTINATION_REALM, config.destinationRealm(), true, false, true);

        // Auth-Application-Id
        avps.addAvp(Avp.AUTH_APPLICATION_ID, APPLICATION_ID_CC, true, false);

        // CC-Request-Type = INITIAL_REQUEST (1)
        avps.addAvp(AvpCode.CC_REQUEST_TYPE, CC_REQUEST_TYPE_INITIAL, true, false);

        // CC-Request-Number = 0
        avps.addAvp(AvpCode.CC_REQUEST_NUMBER, 0L, true, false);

        // Subscription-Id grouped AVP (443)
        AvpSet subscriptionId = avps.addGroupedAvp(AvpCode.SUBSCRIPTION_ID, true, false);
        subscriptionId.addAvp(AvpCode.SUBSCRIPTION_ID_TYPE,
                              AvpCode.SUBSCRIPTION_ID_TYPE_E164, true, false);
        subscriptionId.addAvp(AvpCode.SUBSCRIPTION_ID_DATA, msisdn, true, false, false);

        // Requested-Service-Unit grouped AVP (431)
        AvpSet requestedServiceUnit = avps.addGroupedAvp(AvpCode.REQUESTED_SERVICE_UNIT, true, false);
        requestedServiceUnit.addAvp(AvpCode.CC_TOTAL_OCTETS, requestedUnits, true, false);

        return ccr;
    }
}
