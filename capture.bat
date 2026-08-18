@echo off
:: ═══════════════════════════════════════════════════════════════════════
:: capture.bat — Capture Diameter traffic using tshark (Npcap/WinPcap)
::
:: Prerequisites:
::   1. Wireshark + Npcap installed (https://www.wireshark.org/download.html)
::   2. tshark.exe in PATH or set TSHARK_PATH below
::
:: Usage:
::   capture.bat [duration_seconds]
::   Example: capture.bat 60
:: ═══════════════════════════════════════════════════════════════════════

SET TSHARK_PATH=C:\Program Files\Wireshark\tshark.exe
SET OUTPUT_FILE=transaction_flow.pcap
SET DURATION=%1
IF "%DURATION%"=="" SET DURATION=120

echo ╔══════════════════════════════════════════════════════╗
echo ║  Telecom-Bridge PCAP Capture                        ║
echo ║  Duration: %DURATION% seconds                       ║
echo ║  Output:   %OUTPUT_FILE%                            ║
echo ╚══════════════════════════════════════════════════════╝
echo.

IF NOT EXIST "%TSHARK_PATH%" (
    echo ERROR: tshark not found at %TSHARK_PATH%
    echo Please install Wireshark from https://www.wireshark.org/download.html
    exit /b 1
)

echo Starting capture on loopback adapter...
echo Press Ctrl+C to stop early.
echo.

:: Capture on the loopback interface (interface \Device\NPF_Loopback on Windows)
:: Filter: Diameter traffic on port 3868 AND HTTP traffic on port 8080
"%TSHARK_PATH%" ^
    -i "Loopback Pseudo-Interface 1" ^
    -f "tcp port 3868 or tcp port 8080" ^
    -w "%OUTPUT_FILE%" ^
    -a duration:%DURATION% ^
    -q

echo.
echo Capture complete!
echo Output file: %OUTPUT_FILE%
echo.
echo To view with Wireshark:
echo   wireshark %OUTPUT_FILE%
echo.
echo Wireshark display filter for Diameter:
echo   diameter
echo.
echo Wireshark display filter for both REST + Diameter:
echo   http or diameter
