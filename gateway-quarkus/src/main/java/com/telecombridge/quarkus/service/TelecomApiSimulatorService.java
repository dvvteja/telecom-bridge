package com.telecombridge.quarkus.service;

import com.telecombridge.quarkus.model.AccountInfoRequest;
import com.telecombridge.quarkus.model.AccountInfoResponse;
import com.telecombridge.quarkus.model.AccountInfoResponse.*;
import com.telecombridge.quarkus.model.BundleActivateRequest;
import com.telecombridge.quarkus.model.BundleActivateResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Backend simulator for the Telecom API.
 *
 * <p>Faithfully recreates the behaviour observed in the PCAP:
 * <ul>
 *   <li><b>~1 second processing delay</b> per request (PCAP: 1.008s RTT for conn1,
 *       1.076s for conn2, 13.566s and 17.686s for subsequent calls)</li>
 *   <li><b>Account lookup</b> ({@code /in/get-account-info}) returning full subscriber
 *       state including active bundles, quota counters, and usage records</li>
 *   <li><b>Bundle activation</b> ({@code /in/bundle-activate}) with balance deduction
 *       and quota grant</li>
 * </ul>
 *
 * <p>Processing is non-blocking using Mutiny ({@code Uni}) with a fixed delay,
 * preserving Quarkus reactive thread semantics — no blocking I/O thread is held.
 *
 * <h3>PCAP Timing Alignment</h3>
 * <pre>
 *   PCAP Frame 10 → Frame 37:  t=0.001s → t=1.008s  (1.007s processing)
 *   PCAP Frame 25 → Frame 49:  t=0.072s → t=1.076s  (1.004s processing)
 *   PCAP Frame 55 → Frame 67:  t=12.56s → t=13.57s  (1.005s processing)
 *   PCAP Frame 73 → Frame 85:  t=16.68s → t=17.69s  (1.005s processing)
 *                                        avg = ~1,005ms
 * </pre>
 */
@ApplicationScoped
public class TelecomApiSimulatorService {

    private static final Logger log = LoggerFactory.getLogger(TelecomApiSimulatorService.class);

    /**
     * Base processing delay in ms — matches observed average from PCAP timing.
     * ±50ms jitter is added to simulate realistic OCS latency variance.
     */
    private static final long BASE_DELAY_MS    = 950L;
    private static final long JITTER_RANGE_MS  = 100L;

    // ── In-memory subscriber store (keyed by MSISDN) ──────────────────────
    private final Map<String, SubscriberAccount> accounts = new ConcurrentHashMap<>();

    // ── Metrics ───────────────────────────────────────────────────────────
    @Inject MeterRegistry meterRegistry;
    private Counter accountInfoCounter;
    private Counter bundleActivateCounter;
    private Counter notFoundCounter;
    private Counter insufficientBalanceCounter;
    private Timer   processingTimer;

    @PostConstruct
    void init() {
        accountInfoCounter       = meterRegistry.counter("telecom.api.account_info.requests");
        bundleActivateCounter    = meterRegistry.counter("telecom.api.bundle_activate.requests");
        notFoundCounter          = meterRegistry.counter("telecom.api.not_found");
        insufficientBalanceCounter = meterRegistry.counter("telecom.api.insufficient_balance");
        processingTimer          = Timer.builder("telecom.api.processing.latency")
                                        .description("Simulated OCS processing time")
                                        .register(meterRegistry);

        // Seed test accounts (replicate typical PCAP subscriber profiles)
        seedAccounts();
        log.info("TelecomApiSimulatorService initialised with {} test accounts", accounts.size());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Public API — non-blocking (Mutiny Uni)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Processes a {@code GET-ACCOUNT-INFO} request asynchronously.
     *
     * <p>Emits the response after a simulated processing delay matching the
     * ~1,005ms average observed across all PCAP transactions.
     */
    public Uni<AccountInfoResponse> getAccountInfo(AccountInfoRequest request) {
        accountInfoCounter.increment();
        log.info("GET-ACCOUNT-INFO: txId={} MSISDN={} service={}",
                 request.transactionId(), request.msisdn(), request.serviceType());

        return Uni.createFrom().item(() -> processAccountInfo(request))
                  .onItem().delayIt().by(jitter())
                  .invoke(r -> log.info("GET-ACCOUNT-INFO done: txId={} result={}",
                                        request.transactionId(), r.resultCode()));
    }

    /**
     * Processes a {@code BUNDLE-ACTIVATE} request asynchronously.
     *
     * <p>Emits the response after a simulated processing delay.
     */
    public Uni<BundleActivateResponse> bundleActivate(BundleActivateRequest request) {
        bundleActivateCounter.increment();
        log.info("BUNDLE-ACTIVATE: txId={} MSISDN={} bundle={}",
                 request.transactionId(), request.msisdn(), request.bundleId());

        return Uni.createFrom().item(() -> processBundleActivate(request))
                  .onItem().delayIt().by(jitter())
                  .invoke(r -> log.info("BUNDLE-ACTIVATE done: txId={} result={}",
                                        request.transactionId(), r.resultCode()));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Private — business logic
    // ═══════════════════════════════════════════════════════════════════════

    private AccountInfoResponse processAccountInfo(AccountInfoRequest request) {
        String now = Instant.now().toString();
        SubscriberAccount account = accounts.get(request.msisdn());

        if (account == null) {
            notFoundCounter.increment();
            return AccountInfoResponse.notFound(request.transactionId(), request.msisdn());
        }

        return AccountInfoResponse.success(
            request.transactionId(),
            request.msisdn(),
            account.imsi(),
            now,
            buildActiveBundles(account),
            buildUsageRecords(account, request.serviceType()),
            buildServiceEntitlements(account)
        );
    }

    private BundleActivateResponse processBundleActivate(BundleActivateRequest request) {
        String now = Instant.now().toString();
        SubscriberAccount account = accounts.get(request.msisdn());

        if (account == null) {
            notFoundCounter.increment();
            return BundleActivateResponse.insufficientBalance(
                request.transactionId(), request.msisdn(), 1000L, 0L);
        }

        BundleProduct product = BUNDLE_CATALOG.get(request.bundleId());
        if (product == null) {
            return BundleActivateResponse.insufficientBalance(
                request.transactionId(), request.msisdn(), 0L, account.mainBalance());
        }

        if (account.mainBalance() < product.price()) {
            insufficientBalanceCounter.increment();
            return BundleActivateResponse.insufficientBalance(
                request.transactionId(), request.msisdn(),
                product.price(), account.mainBalance());
        }

        // Deduct balance and record activation
        accounts.put(request.msisdn(), account.deductBalance(product.price()));

        return BundleActivateResponse.success(
            request.transactionId(), request.msisdn(), account.imsi(),
            request.bundleId(), product.name(), now,
            product.price(), account.mainBalance()
        );
    }

    // ─── Response body builders ───────────────────────────────────────────

    private List<AccountInfoResponse.BundleInfo> buildActiveBundles(SubscriberAccount account) {
        return List.of(
            new AccountInfoResponse.BundleInfo(
                "BUNDLE_DATA_10GB", "10GB Monthly Data", "DATA", "DATA",
                10_737_418_240L, 8_589_934_592L, 2_147_483_648L,
                "OCTETS", "2026-08-01T00:00:00Z", "2026-08-31T23:59:59Z",
                true, 1000L, "GBP", null, 1, 1),
            new AccountInfoResponse.BundleInfo(
                "BUNDLE_VOICE_200MIN", "200 Minutes Bundle", "VOICE", "VOICE",
                12_000L, 9_600L, 2_400L,
                "SECONDS", "2026-08-01T00:00:00Z", "2026-08-31T23:59:59Z",
                true, 500L, "GBP", null, null, 2),
            new AccountInfoResponse.BundleInfo(
                "BUNDLE_SMS_100", "100 SMS Bundle", "SMS", "SMS",
                100L, 78L, 22L,
                "MESSAGES", "2026-08-01T00:00:00Z", "2026-08-31T23:59:59Z",
                false, 200L, "GBP", null, null, 3),
            new AccountInfoResponse.BundleInfo(
                "BUNDLE_ROAM_EU", "EU Roaming Data 500MB", "DATA", "ROAMING",
                524_288_000L, 314_572_800L, 209_715_200L,
                "OCTETS", "2026-08-10T00:00:00Z", "2026-08-20T23:59:59Z",
                false, 2000L, "GBP", 128, null, 4)
        );
    }

    private List<AccountInfoResponse.UsageRecord> buildUsageRecords(
            SubscriberAccount account, String serviceType) {
        return List.of(
            new AccountInfoResponse.UsageRecord("DATA", 1,
                2_147_483_648L, "OCTETS",
                "2026-08-21T00:00:00Z", "2026-08-21T22:00:00Z", 0L, "GBP"),
            new AccountInfoResponse.UsageRecord("VOICE", null,
                2_400L, "SECONDS",
                "2026-08-21T08:00:00Z", "2026-08-21T18:00:00Z", 0L, "GBP"),
            new AccountInfoResponse.UsageRecord("SMS", null,
                22L, "MESSAGES",
                "2026-08-21T09:00:00Z", "2026-08-21T20:00:00Z", 0L, "GBP")
        );
    }

    private List<AccountInfoResponse.ServiceEntitlement> buildServiceEntitlements(
            SubscriberAccount account) {
        return List.of(
            new AccountInfoResponse.ServiceEntitlement("SVC_DATA", "Mobile Data", true,
                "2023-01-15T00:00:00Z", "NONE"),
            new AccountInfoResponse.ServiceEntitlement("SVC_VOICE", "Voice Calling", true,
                "2023-01-15T00:00:00Z", "NONE"),
            new AccountInfoResponse.ServiceEntitlement("SVC_SMS", "SMS", true,
                "2023-01-15T00:00:00Z", "NONE"),
            new AccountInfoResponse.ServiceEntitlement("SVC_ROAM", "International Roaming", true,
                "2023-06-01T00:00:00Z", "EU_ONLY"),
            new AccountInfoResponse.ServiceEntitlement("SVC_VOLTE", "VoLTE", true,
                "2024-01-01T00:00:00Z", "NONE"),
            new AccountInfoResponse.ServiceEntitlement("SVC_WIFI_CALL", "WiFi Calling", false,
                null, "BARRED"),
            new AccountInfoResponse.ServiceEntitlement("SVC_PREMIUM", "Premium Rate", false,
                null, "BARRED")
        );
    }

    // ─── Timing helper ────────────────────────────────────────────────────

    /**
     * Returns a duration with base delay ± random jitter, simulating the
     * ~1,005ms average OCS processing time observed in the PCAP.
     */
    private Duration jitter() {
        long jitter = ThreadLocalRandom.current().nextLong(-JITTER_RANGE_MS / 2, JITTER_RANGE_MS / 2);
        return Duration.ofMillis(BASE_DELAY_MS + jitter);
    }

    // ─── Test data seeding ────────────────────────────────────────────────

    private void seedAccounts() {
        List.of(
            new SubscriberAccount("447700900001", "234101234567890", 25000L),
            new SubscriberAccount("447700900002", "234101234567891", 50000L),
            new SubscriberAccount("447700900003", "234101234567892", 5000L),
            new SubscriberAccount("447700900099", "234101234567899", 100L)   // low balance
        ).forEach(a -> accounts.put(a.msisdn(), a));
    }

    // ─── Domain models ────────────────────────────────────────────────────

    private record SubscriberAccount(String msisdn, String imsi, Long mainBalance) {
        SubscriberAccount deductBalance(long amount) {
            return new SubscriberAccount(msisdn, imsi, mainBalance - amount);
        }
    }

    private record BundleProduct(String id, String name, long price, long quotaOctets) {}

    private static final Map<String, BundleProduct> BUNDLE_CATALOG = Map.of(
        "BUNDLE_DATA_1GB_7D",  new BundleProduct("BUNDLE_DATA_1GB_7D",  "1GB 7-Day Data",  1000L, 1_073_741_824L),
        "BUNDLE_DATA_5GB_30D", new BundleProduct("BUNDLE_DATA_5GB_30D", "5GB 30-Day Data",  3000L, 5_368_709_120L),
        "BUNDLE_DATA_10GB",    new BundleProduct("BUNDLE_DATA_10GB",    "10GB Monthly Data", 5000L, 10_737_418_240L),
        "BUNDLE_VOICE_200MIN", new BundleProduct("BUNDLE_VOICE_200MIN", "200 Minutes Bundle", 2000L, 12_000L),
        "BUNDLE_ROAM_EU",      new BundleProduct("BUNDLE_ROAM_EU",      "EU Roaming 500MB",  8000L, 524_288_000L)
    );
}
