package com.telecombridge.loadtest

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._
import scala.util.Random

/**
 * Gatling load test for the Telecom-Bridge REST-to-Diameter Gateway.
 *
 * Target: 100 TPS sustained for 500,000 total transactions.
 * Success criteria:
 *   - p95 response time < 100ms
 *   - Error rate < 1%
 *   - Stable memory / no OOM
 *
 * Run with:
 *   mvn gatling:test -pl load-test -DGATEWAY_URL=http://localhost:8080
 *
 * Or with Docker:
 *   docker run --rm --network=host -e GATEWAY_URL=http://localhost:8080 \
 *     -v $(pwd)/load-test/target/gatling-results:/opt/gatling/results \
 *     denvazh/gatling:3.11.3 -s com.telecombridge.loadtest.TelecomBridgeSimulation
 */
class TelecomBridgeSimulation extends Simulation {

  // ── Configuration ─────────────────────────────────────────────────────────

  private val gatewayUrl = sys.env.getOrElse("GATEWAY_URL",
    sys.props.getOrElse("GATEWAY_URL", "http://localhost:8080"))

  /** Total number of transactions to execute. */
  private val totalTransactions = sys.props.getOrElse("TOTAL_TXN", "500000").toInt

  /** Target throughput in transactions per second. */
  private val targetTps = sys.props.getOrElse("TARGET_TPS", "100").toInt

  /** Ramp-up duration in seconds. */
  private val rampSeconds = sys.props.getOrElse("RAMP_SECONDS", "30").toInt

  // ── HTTP Protocol ─────────────────────────────────────────────────────────

  private val httpProtocol = http
    .baseUrl(gatewayUrl)
    .acceptHeader("application/json")
    .contentTypeHeader("application/json")
    .shareConnections              // reuse HTTP connections for throughput
    .maxConnectionsPerHost(200)
    .warmUp(gatewayUrl + "/actuator/health")

  // ── Feeder: random MSISDNs ────────────────────────────────────────────────

  private val msisdnFeeder = Iterator.continually {
    val msisdn     = s"447${Random.between(700000000L, 799999999L)}"
    val units      = Random.between(100L, 10240L)
    val serviceId  = Seq("DATA", "VOICE", "SMS")(Random.nextInt(3))
    Map(
      "msisdn"          -> msisdn,
      "requested_units" -> units,
      "service_id"      -> serviceId
    )
  }

  // ── Charge Request ────────────────────────────────────────────────────────

  private val chargeRequest = http("POST /api/v1/charge")
    .post("/api/v1/charge")
    .body(StringBody(
      """{
        |  "msisdn": "#{msisdn}",
        |  "service_id": "#{service_id}",
        |  "requested_units": #{requested_units}
        |}""".stripMargin
    ))
    .check(status.is(200))
    .check(jsonPath("$.result_code").is("2001"))
    .check(jsonPath("$.granted_units").exists)
    .check(responseTimeInMillis.lte(500))  // hard cap per request (graceful)

  // ── Scenario ──────────────────────────────────────────────────────────────

  private val chargeScenario = scenario("Credit-Control Charge")
    .feed(msisdnFeeder)
    .exec(chargeRequest)

  // ── Injection Profile ─────────────────────────────────────────────────────
  //
  // Phase 1: Ramp from 0 → 100 TPS over 30 seconds
  // Phase 2: Hold at 100 TPS until 500,000 total transactions complete
  //          At 100 TPS, 500K txn = 5000 seconds ≈ 83 minutes
  //
  // Duration calculation:
  //   warmup:   rampSeconds  (30s ramp)
  //   sustain:  (totalTransactions / targetTps) - rampSeconds / 2
  //
  private val sustainSeconds = (totalTransactions.toDouble / targetTps).toInt - rampSeconds / 2

  setUp(
    chargeScenario.inject(
      rampUsersPerSec(1).to(targetTps).during(rampSeconds.seconds),
      constantUsersPerSec(targetTps).during(sustainSeconds.seconds)
    )
  )
  .protocols(httpProtocol)
  .assertions(
    // ── SLA assertions ────────────────────────────────────────────────
    global.responseTime.percentile(95).lte(100),        // p95 < 100ms
    global.responseTime.percentile(99).lte(500),        // p99 < 500ms
    global.successfulRequests.percent.gte(99.0),        // error rate < 1%
    global.requestsPerSec.gte(targetTps.toDouble * 0.95) // maintain ≥ 95 TPS
  )

  before {
    println(s"""
      |╔══════════════════════════════════════════════════════════════╗
      |║           Telecom-Bridge Load Test Configuration            ║
      |╠══════════════════════════════════════════════════════════════╣
      |║  Gateway URL  : $gatewayUrl
      |║  Target TPS   : $targetTps
      |║  Total Txns   : $totalTransactions
      |║  Ramp         : ${rampSeconds}s
      |║  Sustain      : ${sustainSeconds}s
      |║  Total Time   : ~${(rampSeconds + sustainSeconds) / 60}m ${(rampSeconds + sustainSeconds) % 60}s
      |╚══════════════════════════════════════════════════════════════╝
      |""".stripMargin)
  }

  after {
    println("Load test complete. Check the Gatling HTML report in load-test/target/gatling-results/")
  }
}
