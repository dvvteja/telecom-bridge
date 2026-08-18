package com.telecombridge.gateway;

import com.telecombridge.gateway.diameter.DiameterClientConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Entry point for the Telecom-Bridge REST-to-Diameter Gateway.
 *
 * <p>On startup:
 * <ol>
 *   <li>Spring Boot initialises the application context.</li>
 *   <li>{@link com.telecombridge.gateway.diameter.DiameterClient} connects to the
 *       Diameter simulator and performs the CER/CEA handshake.</li>
 *   <li>The Tomcat HTTP server starts listening on port 8080.</li>
 * </ol>
 */
@SpringBootApplication
@EnableConfigurationProperties(DiameterClientConfig.class)
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
