#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────────────
# Alice Agent E2E Test Runner (Unix / Git Bash)
#
# Usage:
#   ./run_alice_e2e.sh               # Test only (assumes built)
#   ./run_alice_e2e.sh --build       # Build then test
#   ./run_alice_e2e.sh --build -v    # Build then test with verbose output
#
# Environment variables:
#   GEMMA4_BASE_URL  (default: http://192.168.1.14:10303/v1)
#   GEMMA4_MODEL     (default: gemma-4)
#   GEMMA4_TIMEOUT   (default: 180)
# ──────────────────────────────────────────────────────────────────────────────

set -euo pipefail

cd "$(dirname "$0")"

echo "============================================================"
echo "  Alice Agent E2E Test Runner"
echo "  API:   ${GEMMA4_BASE_URL:-http://192.168.1.14:10303/v1}"
echo "  Model: ${GEMMA4_MODEL:-gemma-4}"
echo "============================================================"
echo ""

# Check Python dependencies
if ! python -c "import requests" 2>/dev/null; then
    echo "📦 Installing Python dependencies..."
    pip install -r requirements.txt
    echo ""
fi

# Run tests
exec python run_alice_e2e.py "$@"
