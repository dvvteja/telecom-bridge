package com.telecombridge.gatling

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._

/**
 * Baseline simulation recreating the PCAP-observed transaction patterns.
 *
 * PCAP scenario:
 *   Frame 1:  TCP connect (connection 1, port 50008)
 *   Frame 16: TCP connect (connection 2, port 19924)
 *   Frame 10/25: Concurrent POST /in/get-account-info (2 clients)
 *   Frame 37/49: HTTP 200 responses (~1,005ms RTT)
 *   Frame 55: Repeat POST /in/get-account-info
 *   Frame 73: POST /in/bundle-activate
 *   Frame 85: HTTP 200 response
 *
 * For the full 100 TPS / 500K test, use HighVolumeSimulation instead.
 */
class TelecomApiSimulation extends Simulation {

  val httpConf = http
    .baseUrl(System.getProperty("baseUrl", "http://localhost:8081"))
    .contentTypeHeader("application/json")
    .acceptHeader("application/json")
    .disableWarmUp
    .disableCaching

  val msisdnFeeder = Iterator.continually(Map(
    "msisdn"   -> s"44770090${(1000 + scala.util.Random.nextInt(9000)).toString}",
    "txId"     -> s"TX-LOAD-${System.currentTimeMillis()}-${scala.util.Random.nextInt(99999)}",
    "bundleId" -> Seq("BUNDLE_DATA_1GB_7D", "BUNDLE_DATA_5GB_30D", "BUNDLE_VOICE_200MIN")(
                      scala.util.Random.nextInt(3))
  ))

  // ── Requests ──────────────────────────────────────────────────────────────

  val getAccountInfo = exec(
    http("POST /in/get-account-info")
      .post("/in/get-account-info")
      .body(StringBody(session =>
        s"""{
           |  "msisdn":"${session("msisdn").as[String]}",
           |  "transaction_id":"${session("txId").as[String]}",
           |  "service_type":"DATA",
           |  "node_id":"PGW-LON-01",
           |  "plmn_id":"23410",
           |  "requested_units":1073741824,
           |  "rating_group":1,
           |  "service_id":"SVC_DATA_4G",
           |  "ue_ip_address":"10.100.0.1",
           |  "apn":"internet",
           |  "charging_characteristics":"0800"
           |}""".stripMargin
      )).asJson
      .check(status.is(200))
      .check(jsonPath("$.result_code").is("SUCCESS"))
      .check(jsonPath("$.active_bundles[0]").exists)
      .check(responseTimeInMillis.lte(3000))
  )

  val bundleActivate = exec(
    http("POST /in/bundle-activate")
      .post("/in/bundle-activate")
      .body(StringBody(session =>
        s"""{
           |  "msisdn":"${session("msisdn").as[String]}",
           |  "transaction_id":"${session("txId").as[String]}",
           |  "bundle_id":"${session("bundleId").as[String]}",
           |  "activation_mode":"IMMEDIATE",
           |  "channel":"APP",
           |  "node_id":"BILLING-SVC-01",
           |  "plmn_id":"23410",
           |  "account_balance":50000,
           |  "currency_code":"GBP",
           |  "tariff_plan":"PLAN_DATA_UNLIMITED",
           |  "active_bundle_ids":[]
           |}""".stripMargin
      )).asJson
      .check(status.in(200, 402))
      .check(jsonPath("$.result_code").in("SUCCESS", "INSUFFICIENT_BALANCE"))
      .check(responseTimeInMillis.lte(3000))
  )

  // ── PCAP Scenario 1: 2 concurrent account-info calls ─────────────────────
  val accountInfoScenario = scenario("GET-ACCOUNT-INFO (PCAP frames 10/25/55)")
    .feed(msisdnFeeder)
    .exec(getAccountInfo)
    // Mirrors gap between PCAP frames 10 -> 55: ~11.5s
    .pause(10.seconds, 13.seconds)
    .feed(msisdnFeeder)
    .exec(getAccountInfo)

  // ── PCAP Scenario 2: account-info then bundle-activate ───────────────────
  val bundleActivateScenario = scenario("BUNDLE-ACTIVATE (PCAP frame 73)")
    .feed(msisdnFeeder)
    // Offset: connection 2 started 71ms after connection 1 in PCAP
    .pause(50.milliseconds, 200.milliseconds)
    .exec(getAccountInfo)
    // Gap between PCAP frames 49 -> 73: ~15.6s
    .pause(14.seconds, 17.seconds)
    .exec(bundleActivate)

  // ── Injection: 2 concurrent users (exact PCAP) then configurable load ────
  val users    = System.getProperty("users",    "10").toInt
  val duration = System.getProperty("duration", "60").toInt

  setUp(
    accountInfoScenario.inject(
      atOnceUsers(2),                                                 // PCAP: 2 connections
      nothingFor(20.seconds),
      rampUsersPerSec(1).to(users).during(duration.seconds)          // sustained load
    ),
    bundleActivateScenario.inject(
      atOnceUsers(2),
      nothingFor(20.seconds),
      rampUsersPerSec(1).to(users / 3).during(duration.seconds)
    )
  )
  .protocols(httpConf)
  .assertions(
    global.responseTime.percentile(95).lte(2500),
    global.responseTime.percentile(99).lte(4000),
    global.successfulRequests.percent.gte(99.0)
  )
}
