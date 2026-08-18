package com.telecombridge.gateway.diameter;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Diameter client configuration, bound from {@code application.yml} under the
 * {@code diameter} prefix.
 *
 * <p>Example YAML:
 * <pre>
 * diameter:
 *   server-host: simulator
 *   server-port: 3868
 *   origin-host: gateway.telecombridge.local
 *   origin-realm: telecombridge.local
 *   destination-host: simulator.telecombridge.local
 *   destination-realm: telecombridge.local
 *   request-timeout-ms: 5000
 *   watchdog-interval-seconds: 30
 *   reconnect-delay-seconds: 5
 * </pre>
 */
@ConfigurationProperties(prefix = "diameter")
@Validated
public class DiameterClientConfig {

    @NotBlank
    private String serverHost = "localhost";

    @Min(1) @Max(65535)
    private int serverPort = 3868;

    @NotBlank
    private String originHost = "gateway.telecombridge.local";

    @NotBlank
    private String originRealm = "telecombridge.local";

    @NotBlank
    private String destinationHost = "simulator.telecombridge.local";

    @NotBlank
    private String destinationRealm = "telecombridge.local";

    /** How long to wait for a CCA before timing out (ms). */
    @Min(100) @Max(60000)
    private long requestTimeoutMs = 5000;

    /** DWR interval in seconds. */
    @Min(5) @Max(300)
    private int watchdogIntervalSeconds = 30;

    /** Delay between reconnect attempts in seconds. */
    @Min(1) @Max(60)
    private int reconnectDelaySeconds = 5;

    /** Number of Netty I/O threads (0 = 2×CPU). */
    private int ioThreads = 0;

    // ─── Getters & Setters ────────────────────────────────────────────────

    public String getServerHost()             { return serverHost; }
    public void   setServerHost(String v)     { this.serverHost = v; }

    public int    getServerPort()             { return serverPort; }
    public void   setServerPort(int v)        { this.serverPort = v; }

    public String getOriginHost()             { return originHost; }
    public void   setOriginHost(String v)     { this.originHost = v; }

    public String getOriginRealm()            { return originRealm; }
    public void   setOriginRealm(String v)    { this.originRealm = v; }

    public String getDestinationHost()        { return destinationHost; }
    public void   setDestinationHost(String v){ this.destinationHost = v; }

    public String getDestinationRealm()       { return destinationRealm; }
    public void   setDestinationRealm(String v){ this.destinationRealm = v; }

    public long   getRequestTimeoutMs()          { return requestTimeoutMs; }
    public void   setRequestTimeoutMs(long v)    { this.requestTimeoutMs = v; }

    public int    getWatchdogIntervalSeconds()   { return watchdogIntervalSeconds; }
    public void   setWatchdogIntervalSeconds(int v){ this.watchdogIntervalSeconds = v; }

    public int    getReconnectDelaySeconds()     { return reconnectDelaySeconds; }
    public void   setReconnectDelaySeconds(int v){ this.reconnectDelaySeconds = v; }

    public int    getIoThreads()                 { return ioThreads; }
    public void   setIoThreads(int v)            { this.ioThreads = v; }
}
