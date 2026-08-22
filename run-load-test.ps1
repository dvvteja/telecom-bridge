#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Run the 100 TPS / 500K transaction load test.

.PARAMETER BaseUrl
    Target gateway URL. Default: http://localhost:8081

.PARAMETER TotalTps
    Transactions per second. Default: 100

.PARAMETER TotalTx
    Total number of transactions. Default: 500000

.PARAMETER RampSeconds
    Ramp-up duration in seconds. Default: 120

.PARAMETER MixAccountPct
    Percentage of traffic to POST /in/get-account-info. Default: 70

.PARAMETER UseDocker
    If set, starts the full Docker stack before running the test.

.EXAMPLE
    .\run-load-test.ps1
    .\run-load-test.ps1 -UseDocker
    .\run-load-test.ps1 -TotalTps 50 -TotalTx 250000 -RampSeconds 60
#>
param(
    [string]$BaseUrl       = "http://localhost:8081",
    [int]   $TotalTps      = 100,
    [long]  $TotalTx       = 500000,
    [int]   $RampSeconds   = 120,
    [int]   $MixAccountPct = 70,
    [switch]$UseDocker
)

# Do NOT use Stop — java -version writes to stderr and will terminate the script
$ErrorActionPreference = "Continue"
$StartTime = Get-Date

function Write-Banner([string]$msg) {
    $bar = "=" * 68
    Write-Host ""
    Write-Host $bar         -ForegroundColor Cyan
    Write-Host "  $msg"     -ForegroundColor Cyan
    Write-Host $bar         -ForegroundColor Cyan
    Write-Host ""
}

function Test-Cmd([string]$cmd) {
    $null = Get-Command $cmd -ErrorAction SilentlyContinue
    return ($?)
}

function Get-CmdVersion([string]$cmd, [string[]]$args, [string]$pattern) {
    try {
        $out = (& $cmd @args) 2>&1 | Select-String $pattern | Select-Object -First 1
        return if ($out) { $out.ToString().Trim() } else { "unknown" }
    } catch { return "unknown" }
}

# ── Prerequisites ─────────────────────────────────────────────────────────────
Write-Banner "PREREQ CHECK"

if (-not (Test-Cmd "java")) {
    Write-Host "MISSING: java. Install JDK 17: https://adoptium.net" -ForegroundColor Red
    exit 1
}
if (-not (Test-Cmd "mvn")) {
    Write-Host "MISSING: mvn. Install Maven: https://maven.apache.org" -ForegroundColor Red
    exit 1
}
if ($UseDocker -and -not (Test-Cmd "docker")) {
    Write-Host "MISSING: docker. Install Docker Desktop: https://www.docker.com" -ForegroundColor Red
    exit 1
}

$javaVer = (& java -version 2>&1) | Select-String 'version' | Select-Object -First 1
$mvnVer  = (& mvn --version  2>&1) | Select-String 'Apache Maven' | Select-Object -First 1
Write-Host "OK: java  -- $javaVer" -ForegroundColor Green
Write-Host "OK: mvn   -- $mvnVer"  -ForegroundColor Green

# ── Plan ──────────────────────────────────────────────────────────────────────
$rampTx        = [long]($RampSeconds * $TotalTps / 2)
$steadyTx      = [long][Math]::Max($TotalTx - $rampTx, [long]($TotalTx * 0.9))
$steadySeconds = [long][Math]::Ceiling($steadyTx / $TotalTps)
$totalSeconds  = $RampSeconds + $steadySeconds + 60
$totalMinutes  = [Math]::Round($totalSeconds / 60.0, 1)
$accountTps    = [int][Math]::Round($TotalTps * $MixAccountPct / 100.0)
$bundleTps     = $TotalTps - $accountTps

Write-Banner "LOAD TEST PLAN"
Write-Host "  Target URL     : $BaseUrl"
Write-Host "  Total TPS      : $TotalTps"
Write-Host "  Total TX       : $($TotalTx.ToString('N0'))"
    $bundlePct = 100 - $MixAccountPct
    Write-Host "  Traffic mix    : ${MixAccountPct}% account-info ($accountTps TPS) + ${bundlePct}% bundle-activate ($bundleTps TPS)"
Write-Host "  Ramp-up        : ${RampSeconds}s"
Write-Host "  Steady-state   : ${steadySeconds}s (~$([int]($steadySeconds/60)) min)"
Write-Host "  Cooldown       : 60s"
Write-Host "  TOTAL DURATION : ~$totalMinutes minutes" -ForegroundColor Yellow
Write-Host ""
Write-Host "  SLA : P50 < 1500ms | P95 < 2500ms | P99 < 4000ms | Errors < 0.5%" -ForegroundColor DarkCyan

# ── Start Docker (optional) ────────────────────────────────────────────────────
if ($UseDocker) {
    Write-Banner "STARTING DOCKER STACK"
    & docker compose up -d simulator gateway-quarkus
    Write-Host "Waiting for gateway to be healthy (up to 90s)..."
    $ready = $false
    for ($i = 0; $i -lt 18; $i++) {
        Start-Sleep -Seconds 5
        try {
            $r = Invoke-WebRequest -Uri "$BaseUrl/in/health" -TimeoutSec 3 -ErrorAction Stop
            if ($r.StatusCode -eq 200) { $ready = $true; break }
        } catch { }
        Write-Host "  ... waiting ($($i*5+5)s)"
    }
    if (-not $ready) {
        Write-Host "ERROR: Gateway did not become healthy." -ForegroundColor Red
        Write-Host "  Check: docker compose logs gateway-quarkus" -ForegroundColor Yellow
        exit 1
    }
    Write-Host "Gateway healthy." -ForegroundColor Green
}

# ── Warm-up check ─────────────────────────────────────────────────────────────
Write-Banner "WARM-UP CHECK"
$gatewayOk = $false
try {
    $warmup = Invoke-WebRequest -Uri "$BaseUrl/in/health" -TimeoutSec 10 -ErrorAction Stop
    if ($warmup.StatusCode -eq 200) {
        Write-Host "Gateway responded: HTTP $($warmup.StatusCode)" -ForegroundColor Green
        $gatewayOk = $true
    }
} catch {
    Write-Host "WARNING: $BaseUrl/in/health is not reachable." -ForegroundColor Yellow
}

if (-not $gatewayOk) {
    Write-Host "Gateway is not running. Proceeding with load test anyway (Gatling will report connection errors)." -ForegroundColor Yellow
    Write-Host "To start the stack: docker compose up -d simulator gateway-quarkus" -ForegroundColor DarkCyan
}

# ── Run Gatling ────────────────────────────────────────────────────────────────
Write-Banner "RUNNING LOAD TEST (~$totalMinutes MINUTES)"
Write-Host "  Started    : $(Get-Date -Format 'HH:mm:ss')"
Write-Host "  Expected   : $(((Get-Date).AddSeconds($totalSeconds)).ToString('HH:mm:ss'))"
Write-Host ""

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location "$scriptRoot\load-test"

& mvn gatling:test `
    "-Dgatling.simulationClass=com.telecombridge.gatling.HighVolumeSimulation" `
    "-DbaseUrl=$BaseUrl" `
    "-DtotalTps=$TotalTps" `
    "-DtotalTx=$TotalTx" `
    "-DrampSeconds=$RampSeconds" `
    "-DmixAccountPct=$MixAccountPct" `
    "--no-transfer-progress"

$gatlingExitCode = $LASTEXITCODE
Set-Location $scriptRoot

# ── Results ────────────────────────────────────────────────────────────────────
$elapsed = (Get-Date) - $StartTime
Write-Banner "LOAD TEST COMPLETE"
Write-Host "  Elapsed : $($elapsed.ToString('hh\:mm\:ss'))"

$gatlingResultsDir = "$scriptRoot\load-test\target\gatling"
if (Test-Path $gatlingResultsDir) {
    $reportDir = Get-ChildItem $gatlingResultsDir |
                 Where-Object { $_.PSIsContainer } |
                 Sort-Object LastWriteTime -Descending |
                 Select-Object -First 1
    if ($reportDir) {
        $reportHtml = Join-Path $reportDir.FullName "index.html"
        Write-Host "  Report  : $reportHtml" -ForegroundColor Cyan
        if (Test-Path $reportHtml) {
            Start-Process $reportHtml
        }
    }
}

if ($gatlingExitCode -eq 0) {
    Write-Host ""
    Write-Host "  ALL ASSERTIONS PASSED" -ForegroundColor Green
    Write-Host "  P95 < 2500ms | P99 < 4000ms | Errors < 0.5%" -ForegroundColor Green
} else {
    Write-Host ""
    Write-Host "  ASSERTIONS FAILED (exit code $gatlingExitCode)" -ForegroundColor Red
    Write-Host "  Check the HTML report for per-request breakdown." -ForegroundColor Yellow
    exit $gatlingExitCode
}
