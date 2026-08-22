package com.telecombridge.gatling

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._

/**
 * High-Volume Load Test: 100 TPS sustained for 500,000 total transactions.
 *
 * Target    : 100 transactions/second (constant)
 * Volume    : 500,000 total transactions
 * Duration  : 5,000s steady-state + 120s ramp + 60s cooldown = ~85 min
 * Mix       : 70% get-account-info + 30% bundle-activate
 * Concurrency: ~100 in-flight at steady state (Little's Law: lambda x W = 100)
 *
 * SLA assertions (auto-fail if breached):
 *   P50 lt 1,500ms | P95 lt 2,500ms | P99 lt 4,000ms | Errors lt 0.5%
 *
 * Run:
 *   mvn gatling:test \
 *     -Dgatling.simulationClass=com.telecombridge.gatling.HighVolumeSimulation \
 *     -DbaseUrl=http://localhost:8081 \
 *     -DtotalTps=100 \
 *     -DtotalTx=500000
 */
class HighVolumeSimulation extends Simulation {

  // ── Configuration ────────────────────────────────────────────────────────
  val baseUrl         = System.getProperty("baseUrl",       "http://localhost:8081")
  val totalTps        = System.getProperty("totalTps",      "100").toInt
  val totalTx         = System.getProperty("totalTx",       "500000").toLong
  val rampSeconds     = System.getProperty("rampSeconds",   "120").toInt
  val mixAccountPct   = System.getProperty("mixAccountPct", "70").toInt
  val mixBundlePct    = 100 - mixAccountPct

  // TPS per scenario
  val accountTps      = math.round(totalTps * mixAccountPct  / 100.0).toInt  // default 70
  val bundleTps       = math.round(totalTps * mixBundlePct   / 100.0).toInt  // default 30

  // Duration: 500,000 tx / 100 TPS = 5,000s steady-state
  // Ramp contributes approx (rampSeconds * totalTps / 2) transactions
  val rampTx          = (rampSeconds * totalTps / 2).toLong
  val steadyTx        = math.max(totalTx - rampTx, totalTx * 9 / 10)
  val steadySeconds   = math.ceil(steadyTx.toDouble / totalTps).toLong
  val cooldownSeconds = 60L
  val totalSeconds    = rampSeconds + steadySeconds + cooldownSeconds

  println(
    s"""
       |====================================================================
       |  TELECOM-BRIDGE HIGH-VOLUME LOAD TEST
       |====================================================================
       |  Target URL    : $baseUrl
       |  Total TPS     : $totalTps (account-info: $accountTps, bundle: $bundleTps)
       |  Target TX     : $totalTx (${totalTx / 1000}K)
       |  Ramp          : ${rampSeconds}s
       |  Steady-state  : ${steadySeconds}s (~${steadySeconds / 60} min)
       |  Cooldown      : ${cooldownSeconds}s
       |  TOTAL TIME    : ~${totalSeconds / 60} minutes
       |  Concurrency   : ~$totalTps in-flight at steady state (Littles Law)
       |====================================================================
       |""".stripMargin)

  // ── HTTP Protocol ────────────────────────────────────────────────────────
  val httpConf = http
    .baseUrl(baseUrl)
    .contentTypeHeader("application/json")
    .acceptHeader("application/json")
    .maxConnectionsPerHost(200)
    .disableCaching
    .check(status.not(500), status.not(502), status.not(503))

  // ── Feeder: 10K MSISDN pool ───────────────────────────────────────────────
  val msisdnFeeder = Iterator.continually {
    val suffix = f"${scala.util.Random.nextInt(10000)}%04d"
    Map(
      "msisdn"      -> s"44770090$suffix",
      "imsi"        -> s"23410${suffix}00000",
      "txId"        -> s"TX-HV-${System.nanoTime()}",
      "bundleId"    -> Seq("BUNDLE_DATA_1GB_7D","BUNDLE_DATA_5GB_30D","BUNDLE_VOICE_200MIN")(
                          scala.util.Random.nextInt(3)),
      "ratingGroup" -> (scala.util.Random.nextInt(3) + 1).toString,
      "nodeId"      -> s"PGW-LON-0${scala.util.Random.nextInt(4) + 1}"
    )
  }

  // ── Requests ─────────────────────────────────────────────────────────────

  // POST /in/get-account-info — ~1,629 byte request, ~5,100 byte response
  val getAccountInfo = exec(
    http("POST /in/get-account-info")
      .post("/in/get-account-info")
      .body(StringBody(session =>
        s"""{
           |  "msisdn":"${session("msisdn").as[String]}",
           |  "imsi":"${session("imsi").as[String]}",
           |  "transaction_id":"${session("txId").as[String]}",
           |  "service_type":"DATA",
           |  "node_id":"${session("nodeId").as[String]}",
           |  "plmn_id":"23410",
           |  "requested_units":1073741824,
           |  "rating_group":${session("ratingGroup").as[String]},
           |  "service_id":"SVC_DATA_4G",
           |  "ue_ip_address":"10.100.0.1",
           |  "apn":"internet",
           |  "charging_characteristics":"0800"
           |}""".stripMargin
      )).asJson
      .check(status.is(200))
      .check(jsonPath("$.result_code").is("SUCCESS"))
      .check(jsonPath("$.active_bundles[0]").exists)
  )

  // POST /in/bundle-activate — ~1,650 byte request, ~2,800 byte response
  val bundleActivate = exec(
    http("POST /in/bundle-activate")
      .post("/in/bundle-activate")
      .body(StringBody(session =>
        s"""{
           |  "msisdn":"${session("msisdn").as[String]}",
           |  "imsi":"${session("imsi").as[String]}",
           |  "transaction_id":"${session("txId").as[String]}",
           |  "bundle_id":"${session("bundleId").as[String]}",
           |  "activation_mode":"IMMEDIATE",
           |  "channel":"APP",
           |  "node_id":"${session("nodeId").as[String]}",
           |  "plmn_id":"23410",
           |  "account_balance":50000,
           |  "currency_code":"GBP",
           |  "tariff_plan":"PLAN_DATA_UNLIMITED",
           |  "active_bundle_ids":[]
           |}""".stripMargin
      )).asJson
      .check(status.in(200, 402))
      .check(jsonPath("$.result_code").in("SUCCESS", "INSUFFICIENT_BALANCE"))
  )

  // ── Scenarios ─────────────────────────────────────────────────────────────
  // Open model: each virtual user fires exactly one request then exits.
  // At 100 TPS arrival rate with 1s service time -> ~100 concurrent in-flight.

  val accountInfoScenario = scenario("GET-ACCOUNT-INFO")
    .feed(msisdnFeeder)
    .exec(getAccountInfo)

  val bundleActivateScenario = scenario("BUNDLE-ACTIVATE")
    .feed(msisdnFeeder)
    .exec(bundleActivate)

  // ── Injection profiles ────────────────────────────────────────────────────
  //
  //  TPS
  //  100 |               +===================================+
  //      |              /                                     \
  //      |             /   steady (~4,940s)                    \
  //    0 +------------+-------------------------------------------+---> t
  //      0          120s                                      5060s 5120s
  //                 ramp                                            cool

  val accountInjection = List(
    rampUsersPerSec(0).to(accountTps).during(rampSeconds.seconds),
    constantUsersPerSec(accountTps).during(steadySeconds.seconds),
    rampUsersPerSec(accountTps).to(0).during(cooldownSeconds.seconds)
  )

  val bundleInjection = List(
    rampUsersPerSec(0).to(bundleTps).during(rampSeconds.seconds),
    constantUsersPerSec(bundleTps).during(steadySeconds.seconds),
    rampUsersPerSec(bundleTps).to(0).during(cooldownSeconds.seconds)
  )

  // ── Test setup ────────────────────────────────────────────────────────────
  setUp(
    accountInfoScenario.inject(
      rampUsersPerSec(0).to(accountTps).during(rampSeconds.seconds),
      constantUsersPerSec(accountTps).during(steadySeconds.seconds),
      rampUsersPerSec(accountTps).to(0).during(cooldownSeconds.seconds)
    ),
    bundleActivateScenario.inject(
      rampUsersPerSec(0).to(bundleTps).during(rampSeconds.seconds),
      constantUsersPerSec(bundleTps).during(steadySeconds.seconds),
      rampUsersPerSec(bundleTps).to(0).during(cooldownSeconds.seconds)
    )
  )
  // throttle() on setUp result: hard cap at totalTps regardless of jitter
  .throttle(
    reachRps(totalTps).in(rampSeconds.seconds),
    holdFor(steadySeconds.seconds),
    jumpToRps(0)
  )
  .protocols(httpConf)
  .maxDuration((totalSeconds + 120).seconds)   // hard ceiling
  .assertions(
    // Latency SLA
    global.responseTime.percentile(50).lte(1500),
    global.responseTime.percentile(95).lte(2500),
    global.responseTime.percentile(99).lte(4000),
    global.responseTime.max.lte(10000),
    // Throughput SLA (allows for ramp period drag)
    global.requestsPerSec.gte(80.0),
    // Reliability SLA
    global.failedRequests.percent.lte(0.5),
    global.successfulRequests.percent.gte(99.5),
    // Per-scenario
    forAll.responseTime.percentile(95).lte(2500),
    forAll.successfulRequests.percent.gte(99.0)
  )
}
