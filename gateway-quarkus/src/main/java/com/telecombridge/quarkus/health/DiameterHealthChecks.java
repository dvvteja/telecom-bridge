package com.telecombridge.quarkus.health;

import com.telecombridge.quarkus.diameter.JDiameterStackManager;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;
import org.eclipse.microprofile.health.Readiness;

/**
 * MicroProfile / SmallRye Health checks for Kubernetes HPA integration.
 *
 * <p>Exposes two probes via Quarkus's built-in {@code /q/health} endpoints:
 * <ul>
 *   <li>{@code /q/health/live}  — liveness: JVM is running (always UP)</li>
 *   <li>{@code /q/health/ready} — readiness: Diameter peer connected and stack UP</li>
 * </ul>
 *
 * <p>Kubernetes {@code readinessProbe} targeting {@code /q/health/ready} ensures
 * traffic is only routed to pods that have a live Diameter peer connection.
 * The HPA can scale on custom Prometheus metrics (e.g., {@code diameter.ccr.sent}).
 */
public class DiameterHealthChecks {

    // ── Liveness probe (/q/health/live) ──────────────────────────────────

    /**
     * Liveness check — reports UP as long as the JVM is alive.
     * A DOWN liveness causes Kubernetes to restart the pod.
     */
    @Liveness
    @ApplicationScoped
    public static class DiameterLiveness implements HealthCheck {

        @Override
        public HealthCheckResponse call() {
            // The JVM is alive if we're here
            return HealthCheckResponse.up("diameter-liveness");
        }
    }

    // ── Readiness probe (/q/health/ready) ────────────────────────────────

    /**
     * Readiness check — reports UP only when the JDiameter stack has completed
     * the CER/CEA handshake and at least one peer is in OPEN state.
     *
     * <p>A DOWN readiness causes Kubernetes to stop routing traffic to this pod
     * without restarting it, allowing reconnection in the background.
     */
    @Readiness
    @ApplicationScoped
    public static class DiameterReadiness implements HealthCheck {

        @Inject
        JDiameterStackManager stackManager;

        @Override
        public HealthCheckResponse call() {
            if (stackManager.isConnected()) {
                return HealthCheckResponse.named("diameter-readiness")
                    .up()
                    .withData("pending_requests", stackManager.getPendingCount())
                    .withData("stack", "JDiameter")
                    .build();
            }

            String error = stackManager.getErrorMessage();
            return HealthCheckResponse.named("diameter-readiness")
                .down()
                .withData("reason", error.isBlank() ? "Diameter peer not connected" : error)
                .withData("stack", "JDiameter")
                .build();
        }
    }
}
