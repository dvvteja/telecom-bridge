package com.telecombridge.gateway.diameter;

import com.telecombridge.common.diameter.*;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Netty handler on the gateway (client) side. It receives decoded
 * {@link DiameterMessage} objects from the pipeline and routes them back to
 * {@link DiameterClient} for correlation by Hop-by-Hop ID.
 */
public class DiameterClientHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(DiameterClientHandler.class);

    private final DiameterClient client;

    public DiameterClientHandler(DiameterClient client) {
        this.client = client;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        log.info("Connected to Diameter server: {}", ctx.channel().remoteAddress());
        client.onConnected(ctx.channel());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        log.warn("Disconnected from Diameter server — will attempt reconnect");
        client.onDisconnected();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof DiameterMessage)) {
            log.warn("Unexpected type: {}", msg.getClass().getSimpleName());
            return;
        }
        DiameterMessage message = (DiameterMessage) msg;
        log.debug("Received from server: {}", message);
        client.onMessageReceived(message);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Exception in Diameter client channel: {}", cause.getMessage(), cause);
        ctx.close();
    }
}
