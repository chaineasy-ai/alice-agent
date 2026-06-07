#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────────────
# Gemma4 E2E Test Runner (Unix / Git Bash)
#
# Usage:
#   ./run_e2e.sh              # Run all tests
#   ./run_e2e.sh -v            # Verbose
#   ./run_e2e.sh TestGemma4E2E.test_basic_chat  # Single test
#
# Environment variables:
#   GEMMA4_BASE_URL  (default: http://192.168.1.14:10303/v1)
#   GEMMA4_MODEL     (default: gemma-4)
#   GEMMA4_TIMEOUT   (default: 120)
# ──────────────────────────────────────────────────────────────────────────────

set -euo pipefail

cd "$(dirname "$0")"

echo "============================================================"
echo "  Gemma4 E2E Test Runner"
echo "  API:   ${GEMMA4_BASE_URL:-http://192.168.1.14:10303/v1}"
echo "  Model: ${GEMMA4_MODEL:-gemma-4}"
echo "============================================================"
echo ""

# Install dependencies if needed
if ! python -c "import requests" 2>/dev/null; then
    echo "📦 Installing dependencies..."
    pip install -r requirements.txt
    echo ""
fi

# Run tests
exec python run_e2e.py "$@"
