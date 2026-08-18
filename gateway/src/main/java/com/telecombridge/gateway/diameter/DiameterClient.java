package com.telecombridge.gateway.diameter;

import com.telecombridge.common.diameter.*;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Async Diameter client that maintains a single persistent TCP connection to the
 * Diameter simulator.
 *
 * <h3>Connection lifecycle</h3>
 * <ol>
 *   <li>On startup ({@link PostConstruct}): connect, send CER, await CEA.</li>
 *   <li>Every {@code diameter.watchdog-interval-seconds}: send DWR, expect DWA.</li>
 *   <li>On disconnect: exponential-backoff reconnect.</li>
 *   <li>On shutdown ({@link PreDestroy}): drain pending requests, close channel.</li>
 * </ol>
 *
 * <h3>Request correlation (the core concurrency pattern)</h3>
 * <pre>
 *   ConcurrentHashMap&lt;Long /*HbH ID*&#47;, CompletableFuture&lt;DiameterMessage&gt;&gt; pendingRequests
 * </pre>
 * Every outgoing CCR stores its future in the map keyed by its unique HbH ID.
 * When a CCA arrives on the I/O thread, the future is looked up and completed —
 * unblocking the REST handler thread that called {@link #sendCcr}.
 *
 * <h3>Non-blocking guarantee</h3>
 * {@link #sendCcr} returns a {@link CompletableFuture} immediately. The Netty
 * write is enqueued on the event loop. No HTTP/REST thread ever blocks waiting
 * for the Diameter response.
 */
@Component
public class DiameterClient {

    private static final Logger log = LoggerFactory.getLogger(DiameterClient.class);

    // ─── State ────────────────────────────────────────────────────────────

    private final DiameterClientConfig config;

    /**
     * Maps Hop-by-Hop IDs to their pending CompletableFutures.
     * Written and read from multiple threads — must be concurrent.
     */
    private final ConcurrentHashMap<Long, CompletableFuture<DiameterMessage>> pendingRequests
            = new ConcurrentHashMap<>(1024);

    private final AtomicLong  hopByHopIdCounter = new AtomicLong(1);
    private final AtomicLong  endToEndIdCounter  = new AtomicLong(1);
    private final AtomicLong  originStateId      = new AtomicLong(System.currentTimeMillis() / 1000);
    private final AtomicLong  ccrRequestNumber   = new AtomicLong(0);

    private final AtomicBoolean        running         = new AtomicBoolean(false);
    private final AtomicBoolean        connected       = new AtomicBoolean(false);
    private final AtomicReference<Channel> channelRef  = new AtomicReference<>();

    private NioEventLoopGroup         eventLoopGroup;
    private ScheduledExecutorService  watchdogScheduler;
    private ScheduledExecutorService  reconnectScheduler;

    /** Completes when the CEA is received after connect. */
    private volatile CompletableFuture<DiameterMessage> ceaFuture;

    public DiameterClient(DiameterClientConfig config) {
        this.config = config;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════════════════════════════════════

    @PostConstruct
    public void start() {
        running.set(true);
        int threads = config.getIoThreads() > 0
                ? config.getIoThreads()
                : Math.max(2, Runtime.getRuntime().availableProcessors() * 2);

        eventLoopGroup     = new NioEventLoopGroup(threads);
        watchdogScheduler  = Executors.newSingleThreadScheduledExecutor(
                r -> new Thread(r, "diameter-watchdog"));
        reconnectScheduler = Executors.newSingleThreadScheduledExecutor(
                r -> new Thread(r, "diameter-reconnect"));

        log.info("Diameter client starting — server={}:{} threads={}",
                config.getServerHost(), config.getServerPort(), threads);

        connectWithRetry(0);
    }

    @PreDestroy
    public void stop() {
        log.info("Shutting down Diameter client...");
        running.set(false);

        // Fail all pending requests immediately
        pendingRequests.forEach((hbhId, future) ->
            future.completeExceptionally(
                new IllegalStateException("Diameter client shutting down")));
        pendingRequests.clear();

        Channel ch = channelRef.get();
        if (ch != null && ch.isOpen()) {
            ch.close().awaitUninterruptibly();
        }

        if (watchdogScheduler  != null) watchdogScheduler.shutdownNow();
        if (reconnectScheduler != null) reconnectScheduler.shutdownNow();
        if (eventLoopGroup     != null) eventLoopGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS);

        log.info("Diameter client stopped.");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Public API
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Sends a Credit-Control-Request (CCR) asynchronously and returns a
     * {@link CompletableFuture} that completes with the CCA when the response
     * arrives.
     *
     * <p>The future will complete exceptionally with:
     * <ul>
     *   <li>{@link TimeoutException} if no CCA arrives within the configured timeout</li>
     *   <li>{@link IllegalStateException} if the client is not connected</li>
     * </ul>
     *
     * @param msisdn         E.164 subscriber identifier
     * @param requestedUnits Number of octets/units being requested
     * @return Future that resolves to the raw CCA {@link DiameterMessage}
     */
    public CompletableFuture<DiameterMessage> sendCcr(String msisdn, long requestedUnits) {
        if (!connected.get()) {
            CompletableFuture<DiameterMessage> failed = new CompletableFuture<>();
            failed.completeExceptionally(
                new IllegalStateException("Diameter client not connected to server"));
            return failed;
        }

        long hbhId    = hopByHopIdCounter.getAndIncrement();
        long e2eId    = endToEndIdCounter.getAndIncrement();
        long reqNum   = ccrRequestNumber.getAndIncrement();

        CompletableFuture<DiameterMessage> future = new CompletableFuture<>();

        // Register future BEFORE writing to channel to avoid race condition
        pendingRequests.put(hbhId, future);

        DiameterMessage ccr = DiameterMessageFactory.buildCcr(
                config.getOriginHost(),
                config.getOriginRealm(),
                config.getDestinationHost(),
                config.getDestinationRealm(),
                hbhId, e2eId,
                AvpCode.CC_REQUEST_TYPE_INITIAL,
                reqNum,
                msisdn,
                requestedUnits);

        Channel ch = channelRef.get();
        if (ch == null || !ch.isActive()) {
            pendingRequests.remove(hbhId);
            CompletableFuture<DiameterMessage> failed = new CompletableFuture<>();
            failed.completeExceptionally(
                new IllegalStateException("Diameter channel is not active"));
            return failed;
        }

        // Non-blocking write — enqueued on the Netty event loop
        ByteBuf buf = DiameterCodec.encode(ccr);
        ch.writeAndFlush(buf).addListener((ChannelFutureListener) writeFuture -> {
            if (!writeFuture.isSuccess()) {
                pendingRequests.remove(hbhId);
                future.completeExceptionally(new RuntimeException(
                        "Failed to write CCR to channel: " + writeFuture.cause().getMessage(),
                        writeFuture.cause()));
                log.error("CCR write failed for HbH={}: {}", hbhId,
                        writeFuture.cause().getMessage());
            } else {
                log.debug("CCR sent: HbH={} MSISDN={} units={}", hbhId, msisdn, requestedUnits);
            }
        });

        // Timeout guard — cleans up the map entry if no response arrives
        future.orTimeout(config.getRequestTimeoutMs(), TimeUnit.MILLISECONDS)
              .whenComplete((result, ex) -> {
                  if (ex instanceof TimeoutException) {
                      if (pendingRequests.remove(hbhId) != null) {
                          log.warn("CCR timeout: HbH={} MSISDN={} after {}ms",
                                  hbhId, msisdn, config.getRequestTimeoutMs());
                      }
                  }
              });

        return future;
    }

    /**
     * Returns the current number of in-flight Diameter requests.
     * Useful for monitoring and health checks.
     */
    public int getPendingRequestCount() {
        return pendingRequests.size();
    }

    /** Returns true if the client is connected and the CEA handshake is complete. */
    public boolean isConnected() {
        return connected.get();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Callback methods (called by DiameterClientHandler on the I/O thread)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Called when the TCP connection is established. Initiates the CER/CEA handshake.
     */
    void onConnected(Channel channel) {
        channelRef.set(channel);
        log.info("TCP connection established — sending CER");
        sendCer(channel);
    }

    /**
     * Called when the TCP connection is closed.
     */
    void onDisconnected() {
        connected.set(false);
        channelRef.set(null);

        // Fail all in-flight requests
        int failed = pendingRequests.size();
        pendingRequests.forEach((hbhId, future) ->
            future.completeExceptionally(
                new RuntimeException("Diameter connection lost")));
        pendingRequests.clear();

        if (failed > 0) {
            log.warn("{} pending requests failed due to disconnect", failed);
        }

        if (running.get()) {
            scheduleReconnect();
        }
    }

    /**
     * Routes an incoming message to the appropriate handler based on command code.
     */
    void onMessageReceived(DiameterMessage msg) {
        int code = msg.getCommandCode();
        if (code == CommandCode.CAPABILITIES_EXCHANGE) {
            handleCea(msg);
        } else if (code == CommandCode.DEVICE_WATCHDOG) {
            handleDwa(msg);
        } else if (code == CommandCode.CREDIT_CONTROL) {
            handleCca(msg);
        } else {
            log.warn("Unexpected command code from server: {}", code);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Private: Connection management
    // ═══════════════════════════════════════════════════════════════════════

    private void connectWithRetry(int attemptNumber) {
        if (!running.get()) return;

        log.info("Connecting to Diameter server {}:{} (attempt {})",
                config.getServerHost(), config.getServerPort(), attemptNumber + 1);

        Bootstrap bootstrap = new Bootstrap()
                .group(eventLoopGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                          .addLast("idle-handler",
                                  new IdleStateHandler(0, 0, config.getWatchdogIntervalSeconds()))
                          .addLast("diameter-framer",
                                  new DiameterClientFrameDecoder())
                          .addLast("diameter-handler",
                                  new DiameterClientHandler(DiameterClient.this));
                    }
                });

        bootstrap.connect(config.getServerHost(), config.getServerPort())
                 .addListener((ChannelFutureListener) future -> {
                     if (!future.isSuccess()) {
                         log.warn("Connection failed: {} — retrying in {}s",
                                 future.cause().getMessage(),
                                 config.getReconnectDelaySeconds());
                         scheduleReconnect();
                     }
                 });
    }

    private void scheduleReconnect() {
        if (!running.get()) return;
        reconnectScheduler.schedule(
                () -> connectWithRetry(0),
                config.getReconnectDelaySeconds(),
                TimeUnit.SECONDS);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Private: CER/CEA handshake
    // ═══════════════════════════════════════════════════════════════════════

    private void sendCer(Channel channel) {
        long hbhId = hopByHopIdCounter.getAndIncrement();
        long e2eId = endToEndIdCounter.getAndIncrement();

        // Track the CEA future using the special HbH ID
        ceaFuture = new CompletableFuture<>();
        pendingRequests.put(hbhId, ceaFuture);

        DiameterMessage cer = DiameterMessageFactory.buildCer(
                config.getOriginHost(), config.getOriginRealm(), hbhId, e2eId);

        ByteBuf buf = DiameterCodec.encode(cer);
        channel.writeAndFlush(buf).addListener((ChannelFutureListener) f -> {
            if (f.isSuccess()) {
                log.info("CER sent — awaiting CEA");
            } else {
                log.error("Failed to send CER: {}", f.cause().getMessage());
                pendingRequests.remove(hbhId);
                channel.close();
            }
        });
    }

    private void handleCea(DiameterMessage cea) {
        Avp resultCodeAvp = cea.findAvp(AvpCode.RESULT_CODE);
        long rc = resultCodeAvp != null
                ? DiameterCodec.decodeUint32(resultCodeAvp.getData()) : -1;

        if (rc == AvpCode.RESULT_CODE_SUCCESS) {
            log.info("CEA received — Result-Code=2001 (Success) — connection ready");
            connected.set(true);
            // Complete the CEA future and remove from map
            CompletableFuture<DiameterMessage> cf = pendingRequests.remove(cea.getHopByHopId());
            if (cf != null) cf.complete(cea);
            startWatchdog();
        } else {
            log.error("CEA failed — Result-Code={} — closing connection", rc);
            Channel ch = channelRef.get();
            if (ch != null) ch.close();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Private: DWR/DWA watchdog
    // ═══════════════════════════════════════════════════════════════════════

    private void startWatchdog() {
        watchdogScheduler.scheduleAtFixedRate(
                this::sendDwr,
                config.getWatchdogIntervalSeconds(),
                config.getWatchdogIntervalSeconds(),
                TimeUnit.SECONDS);
        log.info("Watchdog started — DWR interval={}s", config.getWatchdogIntervalSeconds());
    }

    private void sendDwr() {
        if (!connected.get()) return;
        Channel ch = channelRef.get();
        if (ch == null || !ch.isActive()) return;

        long hbhId = hopByHopIdCounter.getAndIncrement();
        long e2eId = endToEndIdCounter.getAndIncrement();

        DiameterMessage dwr = DiameterMessageFactory.buildDwr(
                config.getOriginHost(), config.getOriginRealm(),
                hbhId, e2eId, originStateId.get());

        ByteBuf buf = DiameterCodec.encode(dwr);
        ch.writeAndFlush(buf).addListener(f -> {
            if (f.isSuccess()) {
                log.debug("DWR sent — HbH={}", hbhId);
            }
        });
    }

    private void handleDwa(DiameterMessage dwa) {
        log.debug("DWA received — HbH={}", dwa.getHopByHopId());
        // DWA doesn't need to correlate with a CompletableFuture
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Private: CCR/CCA correlation — THE CORE CONCURRENCY MECHANISM
    // ═══════════════════════════════════════════════════════════════════════

    private void handleCca(DiameterMessage cca) {
        long hbhId = cca.getHopByHopId();

        // O(1) lookup — key insight: HbH IDs are unique per request
        CompletableFuture<DiameterMessage> future = pendingRequests.remove(hbhId);

        if (future == null) {
            // Could be a late response after timeout — safe to ignore
            log.warn("CCA received for unknown HbH ID={} (possibly timed out)", hbhId);
            return;
        }

        Avp resultCodeAvp = cca.findAvp(AvpCode.RESULT_CODE);
        long rc = resultCodeAvp != null
                ? DiameterCodec.decodeUint32(resultCodeAvp.getData()) : -1;

        log.debug("CCA received — HbH={} Result-Code={}", hbhId, rc);

        // Complete the future — this wakes up the REST handler's CompletableFuture chain
        future.complete(cca);
    }
}
