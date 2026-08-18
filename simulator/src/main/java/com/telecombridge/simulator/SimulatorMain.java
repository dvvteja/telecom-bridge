package com.telecombridge.simulator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for the standalone Diameter Simulator.
 *
 * <p>Usage:
 * <pre>
 *   java -jar diameter-simulator.jar [port]
 * </pre>
 * Defaults to port 3868 if no argument is given.
 */
public class SimulatorMain {

    private static final Logger log = LoggerFactory.getLogger(SimulatorMain.class);

    public static void main(String[] args) throws InterruptedException {
        int port = DiameterSimulator.DEFAULT_PORT;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                log.warn("Invalid port argument '{}', using default {}", args[0], port);
            }
        }

        DiameterSimulator simulator = new DiameterSimulator(port);

        // Graceful shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown signal received");
            simulator.stop();
        }, "simulator-shutdown"));

        simulator.start();
    }
}
