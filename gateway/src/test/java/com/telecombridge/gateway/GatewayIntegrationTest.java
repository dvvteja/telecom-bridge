package com.telecombridge.gateway;

import com.telecombridge.common.diameter.*;
import com.telecombridge.gateway.diameter.DiameterClient;
import com.telecombridge.gateway.diameter.DiameterClientConfig;
import com.telecombridge.gateway.model.ChargeRequest;
import com.telecombridge.gateway.model.ChargeResponse;
import com.telecombridge.simulator.DiameterSimulator;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration test: starts an embedded Diameter simulator, boots the Spring Boot
 * gateway, and fires real HTTP requests to verify the end-to-end flow.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GatewayIntegrationTest {

    private static final int SIMULATOR_PORT = 13868; // use non-standard port for tests
    private static DiameterSimulator simulator;
    private static Thread simulatorThread;

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private DiameterClient diameterClient;

    @DynamicPropertySource
    static void setDiameterProperties(DynamicPropertyRegistry registry) {
        registry.add("diameter.server-host", () -> "localhost");
        registry.add("diameter.server-port", () -> SIMULATOR_PORT);
        registry.add("diameter.request-timeout-ms", () -> "3000");
    }

    @BeforeAll
    static void startSimulator() throws InterruptedException {
        simulator = new DiameterSimulator(SIMULATOR_PORT);
        simulatorThread = new Thread(() -> {
            try { simulator.start(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }, "test-simulator");
        simulatorThread.setDaemon(true);
        simulatorThread.start();

        // Wait for simulator to be ready
        Thread.sleep(500);
    }

    @AfterAll
    static void stopSimulator() {
        if (simulator != null) simulator.stop();
    }

    @Test
    @Order(1)
    @DisplayName("Gateway connects to simulator and is in connected state")
    void testGatewayConnected() throws InterruptedException {
        // Allow time for CER/CEA handshake
        Thread.sleep(1000);
        assertThat(diameterClient.isConnected()).isTrue();
    }

    @Test
    @Order(2)
    @DisplayName("POST /api/v1/charge returns 200 with granted units")
    void testChargeSuccess() {
        ChargeRequest req = new ChargeRequest(
                "447700900001", "DATA", 1024L, null);

        ResponseEntity<ChargeResponse> response = restTemplate.postForEntity(
                "http://localhost:" + port + "/api/v1/charge",
                req, ChargeResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().resultCode()).isEqualTo(2001);
        assertThat(response.getBody().grantedUnits()).isGreaterThan(0L);
        assertThat(response.getBody().msisdn()).isEqualTo("447700900001");
    }

    @Test
    @Order(3)
    @DisplayName("POST /api/v1/charge with invalid MSISDN returns 400")
    void testChargeValidationFailure() {
        ChargeRequest req = new ChargeRequest(
                "not-a-number", "DATA", 1024L, null);

        ResponseEntity<ChargeResponse> response = restTemplate.postForEntity(
                "http://localhost:" + port + "/api/v1/charge",
                req, ChargeResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @Order(4)
    @DisplayName("Concurrent charge requests all succeed (10 parallel)")
    void testConcurrentCharges() throws InterruptedException, ExecutionException {
        int concurrency = 10;
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        List<Future<ResponseEntity<ChargeResponse>>> futures = new ArrayList<>();

        for (int i = 0; i < concurrency; i++) {
            final int idx = i;
            futures.add(executor.submit(() -> restTemplate.postForEntity(
                    "http://localhost:" + port + "/api/v1/charge",
                    new ChargeRequest("44770090000" + idx, "VOICE", 100L, null),
                    ChargeResponse.class)));
        }

        executor.shutdown();
        executor.awaitTermination(15, TimeUnit.SECONDS);

        long successes = 0;
        for (Future<ResponseEntity<ChargeResponse>> f : futures) {
            ResponseEntity<ChargeResponse> resp = f.get();
            if (resp.getStatusCode() == HttpStatus.OK) successes++;
        }

        assertThat(successes).isEqualTo(concurrency);
    }

    @Test
    @Order(5)
    @DisplayName("GET /api/v1/health/diameter returns UP status")
    void testDiameterHealthEndpoint() {
        ResponseEntity<Object> response = restTemplate.getForEntity(
                "http://localhost:" + port + "/api/v1/health/diameter",
                Object.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
