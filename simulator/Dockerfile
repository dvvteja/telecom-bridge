# syntax=docker/dockerfile:1
# ── Stage 1: Build ────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /build

COPY pom.xml .
COPY common/pom.xml common/
COPY simulator/pom.xml simulator/
COPY gateway/pom.xml gateway/
COPY load-test/pom.xml load-test/

RUN --mount=type=cache,target=/root/.m2 \
    mvn -f pom.xml dependency:go-offline -pl common,simulator -am -q

COPY common/src common/src
COPY simulator/src simulator/src

RUN --mount=type=cache,target=/root/.m2 \
    mvn -f pom.xml package -pl common,simulator -am -DskipTests -q

# ── Stage 2: Runtime ──────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

COPY --from=builder /build/simulator/target/diameter-simulator.jar app.jar

ENV JAVA_OPTS="-XX:+UseContainerSupport \
               -XX:MaxRAMPercentage=75.0 \
               -XX:+UseG1GC \
               -Djava.security.egd=file:/dev/./urandom"

EXPOSE 3868

HEALTHCHECK --interval=10s --timeout=5s --start-period=10s --retries=3 \
    CMD nc -z localhost 3868 || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
