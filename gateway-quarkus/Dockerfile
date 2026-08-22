# syntax=docker/dockerfile:1
# ── Stage 1: Build (Maven + Quarkus augmentation) ─────────────────────────────
FROM eclipse-temurin:17-jdk-alpine AS builder

WORKDIR /build

# Copy all POMs first for layer-cache-friendly dependency download
COPY pom.xml .
COPY common/pom.xml common/
COPY simulator/pom.xml simulator/
COPY gateway/pom.xml gateway/
COPY gateway-quarkus/pom.xml gateway-quarkus/
COPY load-test/pom.xml load-test/

# Download dependencies (cached unless POMs change)
RUN --mount=type=cache,target=/root/.m2 \
    mvn -f pom.xml dependency:go-offline \
        -pl common,gateway-quarkus -am -q 2>/dev/null || true

# Copy source for the modules we need
COPY common/src common/src
COPY gateway-quarkus/src gateway-quarkus/src

# Build the Quarkus fast-jar (augmented, optimised application)
RUN --mount=type=cache,target=/root/.m2 \
    mvn -f pom.xml package \
        -pl common,gateway-quarkus -am \
        -DskipTests -q

# ── Stage 2: Runtime (JRE only — minimal image) ───────────────────────────────
FROM eclipse-temurin:17-jre-alpine

LABEL org.opencontainers.image.title="Telecom-Bridge Quarkus+JDiameter Gateway"
LABEL org.opencontainers.image.description="REST-to-Diameter Ro/Gy gateway using Quarkus 3.x and JDiameter stack"
LABEL org.opencontainers.image.version="1.0.0"

WORKDIR /app

# Non-root user for security hardening
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

# Copy Quarkus fast-jar structure
# fast-jar outputs: quarkus-run.jar + lib/ + app/ + quarkus/
COPY --from=builder --chown=appuser:appgroup \
    /build/gateway-quarkus/target/quarkus-app/ ./

# JVM tuning for containers — Quarkus fast-jar needs much less heap than Spring Boot fat-jar
# Target: 128MB heap (vs Spring Boot 512MB) with GraalVM-friendly flags
ENV JAVA_OPTS="\
  -XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=70.0 \
  -XX:+UseG1GC \
  -XX:G1HeapRegionSize=4m \
  -XX:+ExplicitGCInvokesConcurrent \
  -XX:+ParallelRefProcEnabled \
  -XX:+UseStringDeduplication \
  -Djava.security.egd=file:/dev/./urandom \
  -Dquarkus.http.host=0.0.0.0"

# HTTP port
EXPOSE 8080
# Management port (health / metrics)
EXPOSE 9000

# Quarkus fast-jar entry point
HEALTHCHECK --interval=10s --timeout=5s --start-period=20s --retries=3 \
    CMD wget -qO- http://localhost:9000/q/health/ready 2>/dev/null \
     || wget -qO- http://localhost:8080/q/health/ready || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar quarkus-run.jar"]
