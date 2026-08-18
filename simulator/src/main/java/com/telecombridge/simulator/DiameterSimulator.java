package com.telecombridge.simulator;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Standalone Diameter server simulator.
 *
 * <p>Listens on port 3868 (standard Diameter port) and responds to:
 * <ul>
 *   <li>CER → CEA (Result-Code: 2001)</li>
 *   <li>DWR → DWA (Result-Code: 2001)</li>
 *   <li>CCR → CCA (Result-Code: 2001) with 50–100 ms simulated processing delay</li>
 * </ul>
 */
public class DiameterSimulator {

    private static final Logger log = LoggerFactory.getLogger(DiameterSimulator.class);

    public static final int    DEFAULT_PORT   = 3868;
    public static final String ORIGIN_HOST    = "simulator.telecombridge.local";
    public static final String ORIGIN_REALM   = "telecombridge.local";

    private final int port;
    private final AtomicLong originStateId = new AtomicLong(System.currentTimeMillis() / 1000);

    private NioEventLoopGroup bossGroup;
    private NioEventLoopGroup workerGroup;
    private Channel           serverChannel;

    public DiameterSimulator(int port) {
        this.port = port;
    }

    /**
     * Starts the simulator and blocks until {@link #stop()} is called.
     */
    public void start() throws InterruptedException {
        bossGroup  = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
             .channel(NioServerSocketChannel.class)
             .handler(new LoggingHandler(LogLevel.INFO))
             .option(ChannelOption.SO_BACKLOG, 128)
             .childOption(ChannelOption.SO_KEEPALIVE, true)
             .childOption(ChannelOption.TCP_NODELAY, true)
             .childHandler(new ChannelInitializer<SocketChannel>() {
                 @Override
                 protected void initChannel(SocketChannel ch) {
                     ch.pipeline()
                       .addLast("diameter-framer", new DiameterFrameDecoder())
                       .addLast("diameter-handler", new DiameterServerHandler(
                               ORIGIN_HOST, ORIGIN_REALM, originStateId));
                 }
             });

            ChannelFuture f = b.bind(port).sync();
            serverChannel = f.channel();
            log.info("╔══════════════════════════════════════════════════╗");
            log.info("║  Diameter Simulator started on port {}           ║", port);
            log.info("║  Origin-Host:  {}  ║", ORIGIN_HOST);
            log.info("║  Origin-Realm: {}       ║", ORIGIN_REALM);
            log.info("╚══════════════════════════════════════════════════╝");

            serverChannel.closeFuture().sync();
        } finally {
            stop();
        }
    }

    /**
     * Gracefully shuts down the simulator.
     */
    public void stop() {
        log.info("Stopping Diameter Simulator...");
        if (serverChannel != null) {
            serverChannel.close();
        }
        if (workerGroup != null) workerGroup.shutdownGracefully();
        if (bossGroup  != null) bossGroup.shutdownGracefully();
    }
}
