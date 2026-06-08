@echo off
REM ──────────────────────────────────────────────────────────────────────────
REM Alice Agent E2E Test Runner (Windows)
REM
REM Usage:
REM   run_alice_e2e.bat               Test only (assumes built)
REM   run_alice_e2e.bat --build       Build then test
REM
REM Environment variables:
REM   GEMMA4_BASE_URL  (default: http://192.168.1.14:10303/v1)
REM   GEMMA4_MODEL     (default: gemma-4)
REM   GEMMA4_TIMEOUT   (default: 180)
REM ──────────────────────────────────────────────────────────────────────────

@echo off
cd /d "%~dp0"

echo ============================================================
echo   Alice Agent E2E Test Runner
echo   API:   %GEMMA4_BASE_URL:http://192.168.1.14:10303/v1%
echo   Model: %GEMMA4_MODEL:gemma-4%
echo ============================================================
echo.

REM Install Python dependencies if needed
python -c "import requests" 2>nul || (
    echo Installing Python dependencies...
    pip install -r requirements.txt
)

python run_alice_e2e.py %*
