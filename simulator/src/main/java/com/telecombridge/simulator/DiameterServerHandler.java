package com.telecombridge.simulator;

import com.telecombridge.common.diameter.*;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Netty channel handler that processes incoming Diameter messages and sends
 * appropriate responses. Each accepted connection gets its own handler instance.
 *
 * <p>Supported interactions:
 * <ul>
 *   <li>{@code CER} → {@code CEA} (immediate)</li>
 *   <li>{@code DWR} → {@code DWA} (immediate)</li>
 *   <li>{@code CCR} → {@code CCA} (after 50–100 ms delay)</li>
 * </ul>
 *
 * <p>All responses are written on the Netty I/O thread (or its scheduled executor),
 * ensuring non-blocking behaviour.
 */
@io.netty.channel.ChannelHandler.Sharable
public class DiameterServerHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(DiameterServerHandler.class);

    private static final int MIN_DELAY_MS = 50;
    private static final int MAX_DELAY_MS = 100;

    /** Default octets to grant for a successful CCA (1 MB). */
    private static final long GRANTED_OCTETS = 1_048_576L;

    private final String      originHost;
    private final String      originRealm;
    private final AtomicLong  originStateId;
    private final AtomicLong  ccrCount = new AtomicLong(0);

    public DiameterServerHandler(String originHost, String originRealm,
                                 AtomicLong originStateId) {
        this.originHost    = originHost;
        this.originRealm   = originRealm;
        this.originStateId = originStateId;
    }

    // ─── Connection lifecycle ────────────────────────────────────────────

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        InetSocketAddress remote = (InetSocketAddress) ctx.channel().remoteAddress();
        log.info("New Diameter connection from {}:{}", remote.getHostString(), remote.getPort());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        log.info("Diameter connection closed: {}", ctx.channel().remoteAddress());
    }

    // ─── Message processing ──────────────────────────────────────────────

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof DiameterMessage)) {
            log.warn("Unexpected message type: {}", msg.getClass().getSimpleName());
            return;
        }

        DiameterMessage request = (DiameterMessage) msg;
        log.debug("Received: {}", request);


        int commandCode = request.getCommandCode();
        if (commandCode == CommandCode.CAPABILITIES_EXCHANGE) {
            handleCer(ctx, request);
        } else if (commandCode == CommandCode.DEVICE_WATCHDOG) {
            handleDwr(ctx, request);
        } else if (commandCode == CommandCode.CREDIT_CONTROL) {
            handleCcr(ctx, request);
        } else {
            log.warn("Unsupported command code: {}", commandCode);
        }
    }

    // ─── CER → CEA ───────────────────────────────────────────────────────

    private void handleCer(ChannelHandlerContext ctx, DiameterMessage cer) {
        log.info("CER received — sending CEA (Result-Code: 2001)");

        Avp originHostAvp = cer.findAvp(AvpCode.ORIGIN_HOST);
        String peerHost   = originHostAvp != null
                ? DiameterCodec.decodeUtf8(originHostAvp.getData()) : "unknown";
        log.info("  Peer Origin-Host: {}", peerHost);

        DiameterMessage cea = DiameterMessageFactory.buildCea(cer, originHost, originRealm);
        writeMessage(ctx, cea);
    }

    // ─── DWR → DWA ───────────────────────────────────────────────────────

    private void handleDwr(ChannelHandlerContext ctx, DiameterMessage dwr) {
        log.debug("DWR received — sending DWA");
        DiameterMessage dwa = DiameterMessageFactory.buildDwa(
                dwr, originHost, originRealm, originStateId.get());
        writeMessage(ctx, dwa);
    }

    // ─── CCR → CCA (with simulated delay) ────────────────────────────────

    private void handleCcr(ChannelHandlerContext ctx, DiameterMessage ccr) {
        long count = ccrCount.incrementAndGet();
        long hbhId = ccr.getHopByHopId();

        // Extract MSISDN for logging
        Avp subId = ccr.findAvp(AvpCode.SUBSCRIPTION_ID);
        String msisdn = "unknown";
        if (subId != null && subId.isGrouped()) {
            for (Avp child : subId.getGrouped()) {
                if (child.getCode() == AvpCode.SUBSCRIPTION_ID_DATA) {
                    msisdn = DiameterCodec.decodeUtf8(child.getData());
                }
            }
        }

        log.info("CCR #{} received — HbH={} MSISDN={} — scheduling CCA in {}ms",
                count, hbhId, msisdn,
                ThreadLocalRandom.current().nextInt(MIN_DELAY_MS, MAX_DELAY_MS + 1));

        // Simulate credit control processing delay (50–100ms), non-blocking
        int delay = ThreadLocalRandom.current().nextInt(MIN_DELAY_MS, MAX_DELAY_MS + 1);
        ctx.executor().schedule(() -> {
            if (ctx.channel().isActive()) {
                DiameterMessage cca = DiameterMessageFactory.buildCca(
                        ccr, originHost, originRealm,
                        AvpCode.RESULT_CODE_SUCCESS, GRANTED_OCTETS);
                log.info("CCA #{} sent — HbH={} Result-Code=2001 GrantedOctets={}",
                        count, hbhId, GRANTED_OCTETS);
                writeMessage(ctx, cca);
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    // ─── Write helper ─────────────────────────────────────────────────────

    private void writeMessage(ChannelHandlerContext ctx, DiameterMessage msg) {
        ByteBuf buf = DiameterCodec.encode(msg);
        ctx.writeAndFlush(buf).addListener(future -> {
            if (!future.isSuccess()) {
                log.error("Failed to write Diameter message: {}", future.cause().getMessage());
            }
        });
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Exception in Diameter server handler: {}", cause.getMessage(), cause);
        ctx.close();
    }
}
