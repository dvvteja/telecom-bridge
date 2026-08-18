# Telecom-Bridge Gateway

> **High-performance REST-to-Diameter Gateway** for the Ro/Gy Credit Control interface (RFC 4006 / RFC 6733).

[![Java](https://img.shields.io/badge/Java-21-orange)](https://adoptium.net)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.5-brightgreen)](https://spring.io/projects/spring-boot)
[![Netty](https://img.shields.io/badge/Netty-4.1.110-blue)](https://netty.io)

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│ Docker Compose Network (172.28.0.0/24)                                  │
│                                                                          │
│  ┌───────────────────────┐       TCP:3868        ┌──────────────────┐   │
│  │  gateway (:8080)      │ ─────────────────────▶ simulator (:3868) │   │
│  │  Spring Boot 3.x      │ ◀───────────────────── (Netty server)    │   │
│  │                       │   Diameter CCA                           │   │
│  │  POST /api/v1/charge  │                        CER/CEA handshake │   │
│  │  ↓                    │                        DWR/DWA watchdog  │   │
│  │  DiameterClient       │                        CCR→CCA (50-100ms)│   │
│  │  (Netty NIO)          │                        delay             │   │
│  │  ┌────────────────┐   │                        └──────────────────┘  │
│  │  │ConcurrentHashMap│  │                                              │
│  │  │HbHId → CF<CCA> │  │                                              │
│  │  └────────────────┘   │                                              │
│  └───────────────────────┘                                              │
└─────────────────────────────────────────────────────────────────────────┘
```

### Module Structure

```
telecom-bridge/
├── common/          # Shared Diameter codec (RFC 6733/4006 AVP encoding)
│   └── DiameterCodec.java, DiameterMessage.java, Avp.java, ...
├── simulator/       # Standalone Diameter server (Netty, port 3868)
│   └── DiameterSimulator.java, DiameterServerHandler.java, ...
├── gateway/         # Spring Boot REST microservice (port 8080)
│   └── DiameterClient.java, ChargeController.java, ...
└── load-test/       # Gatling simulation (100 TPS / 500K txns)
    └── TelecomBridgeSimulation.scala
```

---

## Prerequisites

| Tool    | Version  | Notes                              |
|---------|----------|------------------------------------|
| Java    | 17+      | Eclipse Temurin recommended        |
| Maven   | 3.9+     | `mvn --version`                    |
| Docker  | 24+      | For containerised deployment       |
| Docker Compose | 2.x | `docker compose version`      |
| Wireshark/tshark | Any | For PCAP capture (optional) |

---

## Quick Start

### Option A — Docker Compose (Recommended)

```bash
# 1. Clone and build
git clone https://github.com/your-org/telecom-bridge.git
cd telecom-bridge

# 2. Start both services
docker compose up --build

# 3. Test a charge request
curl -X POST http://localhost:8080/api/v1/charge \
  -H "Content-Type: application/json" \
  -d '{
    "msisdn": "447700900001",
    "service_id": "DATA",
    "requested_units": 1024
  }'
```

Expected response:
```json
{
  "session_id": "447700900001-1718000000000",
  "result_code": 2001,
  "result_message": "Success",
  "granted_units": 1048576,
  "msisdn": "447700900001",
  "service_id": "DATA"
}
```

### Option B — Run Locally (no Docker)

```bash
# Terminal 1: Start the Diameter Simulator
mvn package -pl common,simulator -am -DskipTests
java -jar simulator/target/diameter-simulator.jar

# Terminal 2: Start the Gateway
mvn package -pl common,gateway -am -DskipTests
java -jar gateway/target/gateway-1.0.0.jar
```

---

## API Reference

### `POST /api/v1/charge`

**Request:**
```json
{
  "msisdn": "447700900001",          // E.164 MSISDN (required)
  "service_id": "DATA",              // Service identifier (required)
  "requested_units": 1024,           // Units to request (required, ≥1)
  "session_id": "optional-id"        // Session ID (optional, auto-generated)
}
```

**Responses:**

| HTTP Code | Scenario                        |
|-----------|---------------------------------|
| 200       | CCA Result-Code=2001 (Success)  |
| 400       | Invalid request body            |
| 402       | Diameter denied (non-2001 RC)   |
| 503       | Diameter server unavailable     |
| 504       | CCA not received within 5s      |

### `GET /api/v1/health/diameter`

Returns the Diameter connection status.

### `GET /actuator/health`

Spring Boot health endpoint.

---

## Diameter Protocol Implementation

### Message Flow (per PCAP trace)

```
Client (Gateway)                    Server (Simulator)
       │                                    │
       │──────── CER (cmd=257) ────────────▶│  Capabilities Exchange
       │◀──────── CEA (rc=2001) ────────────│
       │                                    │
       │──────── DWR (cmd=280) ────────────▶│  Watchdog (every 30s)
       │◀──────── DWA (rc=2001) ────────────│
       │                                    │
       │──────── CCR (cmd=272) ────────────▶│  Credit Control Request
       │          HbH=N, MSISDN=...         │
       │                                    │  [50–100ms processing]
       │◀──────── CCA (rc=2001) ────────────│  Credit Control Answer
       │          HbH=N, Granted=1MB        │
       │                                    │
```

### AVPs Implemented (RFC 6733 + RFC 4006)

| AVP Code | Name                   | RFC     |
|----------|------------------------|---------|
| 264      | Origin-Host            | 6733    |
| 296      | Origin-Realm           | 6733    |
| 293      | Destination-Host       | 6733    |
| 283      | Destination-Realm      | 6733    |
| 258      | Auth-Application-Id    | 6733    |
| 268      | Result-Code            | 6733    |
| 278      | Origin-State-Id        | 6733    |
| 416      | CC-Request-Type        | 4006    |
| 415      | CC-Request-Number      | 4006    |
| 443      | Subscription-Id        | 4006    |
| 450      | Subscription-Id-Type   | 4006    |
| 444      | Subscription-Id-Data   | 4006    |
| 431      | Requested-Service-Unit | 4006    |
| 432      | Granted-Service-Unit   | 4006    |
| 420      | CC-Total-Octets        | 4006    |

### Concurrency Design

```java
// Core correlation pattern in DiameterClient.java
ConcurrentHashMap<Long /*HbH ID*/, CompletableFuture<DiameterMessage>> pendingRequests;

// On CCR send:
long hbhId = hopByHopIdCounter.getAndIncrement();
CompletableFuture<DiameterMessage> future = new CompletableFuture<>();
pendingRequests.put(hbhId, future);
channel.writeAndFlush(ccrBuffer);            // non-blocking Netty write
future.orTimeout(5, TimeUnit.SECONDS);       // timeout guard

// On CCA received (Netty I/O thread):
CompletableFuture<DiameterMessage> f = pendingRequests.remove(cca.getHopByHopId());
if (f != null) f.complete(cca);             // wakes REST handler
```

---

## Running Tests

```bash
# All unit + integration tests
mvn test -pl common,gateway,simulator

# Only codec unit tests (fast, no network)
mvn test -pl common -Dtest=DiameterCodecTest

# Integration test (requires no other process on port 13868)
mvn test -pl gateway -Dtest=GatewayIntegrationTest
```

---

## Load Testing

### Quick Smoke Test (1000 requests)

```bash
mvn gatling:test -pl load-test \
  -DGATEWAY_URL=http://localhost:8080 \
  -DTOTAL_TXN=1000 \
  -DTARGET_TPS=10
```

### Full 100 TPS / 500K Transaction Test

```bash
# Ensure Docker Compose is running first
docker compose up -d

mvn gatling:test -pl load-test \
  -DGATEWAY_URL=http://localhost:8080
```

The HTML report is generated at: `load-test/target/gatling-results/`

### Expected Results

| Metric   | Target    |
|----------|-----------|
| p50      | < 60ms    |
| p95      | < 100ms   |
| p99      | < 500ms   |
| Error %  | < 1%      |
| TPS      | ≥ 95 TPS  |

---

## Capturing the PCAP Trace

### Windows (requires Wireshark + Npcap)

```cmd
# Start Docker Compose first, then:
capture.bat 120
```

### Linux/macOS

```bash
# Capture for 120 seconds
sudo tcpdump -i lo -w transaction_flow.pcap \
  "tcp port 3868 or tcp port 8080" &
TCPDUMP_PID=$!

# Wait for traffic...
sleep 120
kill $TCPDUMP_PID

echo "Saved: transaction_flow.pcap"
```

### Viewing in Wireshark

```
Display filter: diameter
```

You should see the following sequence:
1. Frame: CER (cmd=257, R-flag set)
2. Frame: CEA (cmd=257, R-flag clear, Result-Code=2001)
3. Frames: CCR/CCA pairs (cmd=272)

---

## Configuration Reference

All Diameter settings are in `gateway/src/main/resources/application.yml`:

| Property                          | Default   | Description                          |
|-----------------------------------|-----------|--------------------------------------|
| `diameter.server-host`            | localhost | Simulator hostname                   |
| `diameter.server-port`            | 3868      | Simulator port                       |
| `diameter.origin-host`            | gateway.* | Gateway Diameter identity            |
| `diameter.request-timeout-ms`     | 5000      | CCR→CCA timeout (ms)                 |
| `diameter.watchdog-interval-seconds` | 30     | DWR keepalive interval               |
| `diameter.reconnect-delay-seconds`   | 5      | Reconnect backoff                    |
| `diameter.io-threads`             | 0 (=2×CPU)| Netty I/O thread count              |

Environment variables override: `DIAMETER_HOST`, `DIAMETER_PORT`

---

## Error Handling Matrix

| Failure Scenario           | HTTP Response   | Diameter Behaviour             |
|----------------------------|-----------------|--------------------------------|
| Simulator offline at start | Logs + retry    | Exponential backoff reconnect  |
| CCR timeout (5s)           | 504 Gateway T/O | Future completed exceptionally |
| Non-2001 Result-Code       | 402 Payment Req | CCA result code surfaced       |
| Connection drops under load| 503 Service UNA | Pending requests fail, reconnect|
| Invalid MSISDN format      | 400 Bad Request | Never sent to Diameter         |

---

## Project Structure

```
telecom-bridge/
├── common/src/main/java/com/telecombridge/common/diameter/
│   ├── Avp.java                    # AVP model (RFC 6733 §4)
│   ├── DiameterMessage.java        # Message model (RFC 6733 §3)
│   ├── DiameterCodec.java          # Encode/decode with padding
│   ├── DiameterMessageFactory.java # CER/CEA/DWR/DWA/CCR/CCA builders
│   ├── CommandCode.java            # Command code constants
│   └── AvpCode.java                # AVP code constants
├── simulator/src/main/java/com/telecombridge/simulator/
│   ├── SimulatorMain.java          # Entry point
│   ├── DiameterSimulator.java      # Netty ServerBootstrap
│   ├── DiameterFrameDecoder.java   # Length-prefixed framing
│   └── DiameterServerHandler.java  # CER/DWR/CCR dispatch
├── gateway/src/main/java/com/telecombridge/gateway/
│   ├── GatewayApplication.java     # Spring Boot entry point
│   ├── diameter/
│   │   ├── DiameterClient.java     # Core async client (ConcurrentHashMap)
│   │   ├── DiameterClientHandler.java
│   │   ├── DiameterClientFrameDecoder.java
│   │   └── DiameterClientConfig.java
│   └── controller/
│       ├── ChargeController.java   # POST /api/v1/charge
│       └── GlobalExceptionHandler.java
└── load-test/src/test/scala/
    └── TelecomBridgeSimulation.scala  # Gatling 100 TPS scenario
```

---

## License

MIT License — see [LICENSE](LICENSE) for details.
