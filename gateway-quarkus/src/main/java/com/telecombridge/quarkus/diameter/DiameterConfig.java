package com.telecombridge.quarkus.diameter;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Diameter client configuration bound from {@code application.properties}
 * under the {@code diameter} prefix via Quarkus SmallRye Config.
 *
 * <p>Example {@code application.properties} entries:
 * <pre>
 * diameter.server-host=simulator
 * diameter.server-port=3868
 * diameter.origin-host=gateway.telecombridge.local
 * diameter.origin-realm=telecombridge.local
 * diameter.destination-host=simulator.telecombridge.local
 * diameter.destination-realm=telecombridge.local
 * diameter.request-timeout-ms=5000
 * diameter.watchdog-interval-seconds=30
 * diameter.reconnect-delay-seconds=5
 * </pre>
 *
 * <p>Environment variable overrides follow the Quarkus naming convention:
 * {@code DIAMETER_SERVER_HOST}, {@code DIAMETER_SERVER_PORT}, etc.
 */
@ConfigMapping(prefix = "diameter")
public interface DiameterConfig {

    /** Hostname or IP of the Diameter peer (simulator). */
    @WithName("server-host")
    @WithDefault("localhost")
    String serverHost();

    /** TCP port of the Diameter peer (default: 3868). */
    @WithName("server-port")
    @WithDefault("3868")
    int serverPort();

    /** Diameter identity of this gateway node (Origin-Host AVP). */
    @WithName("origin-host")
    @WithDefault("gateway.telecombridge.local")
    String originHost();

    /** Diameter realm of this gateway node (Origin-Realm AVP). */
    @WithName("origin-realm")
    @WithDefault("telecombridge.local")
    String originRealm();

    /** Diameter identity of the target OCS/simulator (Destination-Host AVP). */
    @WithName("destination-host")
    @WithDefault("simulator.telecombridge.local")
    String destinationHost();

    /** Realm of the target OCS/simulator (Destination-Realm AVP). */
    @WithName("destination-realm")
    @WithDefault("telecombridge.local")
    String destinationRealm();

    /**
     * How long to wait for a CCA before timing out (milliseconds).
     * Must be between 100ms and 60,000ms.
     */
    @WithName("request-timeout-ms")
    @WithDefault("5000")
    long requestTimeoutMs();

    /** DWR/DWA watchdog interval in seconds. */
    @WithName("watchdog-interval-seconds")
    @WithDefault("30")
    int watchdogIntervalSeconds();

    /** Reconnect delay in seconds after a peer disconnect. */
    @WithName("reconnect-delay-seconds")
    @WithDefault("5")
    int reconnectDelaySeconds();
}
