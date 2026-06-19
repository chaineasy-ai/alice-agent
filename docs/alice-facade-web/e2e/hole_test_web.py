#!/usr/bin/env python3
"""
Hole Test — alice-facade-web HTTP endpoints.

This module has no test source files yet. Holes are 🟥 RED until
either unit tests or a running web server is available.

🟥 RED status: No web server running — deploy first, then re-run.

See:
  docs/alice-agent-command/e2e/case-web.md
  docs/alice-facade-web/e2e/scene-web-endpoints.md
"""

import os
import sys
import unittest

WEB_PORT = os.environ.get("ALICE_WEB_PORT", "8080")
WEB_BASE = f"http://localhost:{WEB_PORT}"


# ── Detect if web server is running ──────────────────────────────
def _web_server_running():
    try:
        import urllib.request
        req = urllib.request.Request(f"{WEB_BASE}/health")
        with urllib.request.urlopen(req, timeout=3) as resp:
            return resp.status == 200
    except Exception:
        return False


_SERVER_OK = _web_server_running()


class TestWebHoles(unittest.TestCase):
    """Hole tests for alice-facade-web — 3 probes."""

    @classmethod
    def setUpClass(cls):
        cls.server_ok = _SERVER_OK

    def test_web_p01_health_check(self):
        """WEB-P01: GET /health returns 200."""
        if not self.server_ok:
            self.skipTest("WEB-P01: 🟥 RED — web server not reachable")
        import requests
        r = requests.get(f"{WEB_BASE}/health", timeout=5)
        self.assertEqual(r.status_code, 200)
        self.assertIn("UP", r.text)

    def test_web_p02_not_found(self):
        """WEB-P02: Unknown path returns 404."""
        if not self.server_ok:
            self.skipTest("WEB-P02: 🟥 RED — web server not reachable")
        import requests
        r = requests.get(f"{WEB_BASE}/nonexistent", timeout=5)
        self.assertEqual(r.status_code, 404)

    def test_web_p03_cors(self):
        """WEB-P03: OPTIONS /health includes CORS headers."""
        if not self.server_ok:
            self.skipTest("WEB-P03: 🟥 RED — web server not reachable")
        import requests
        r = requests.options(
            f"{WEB_BASE}/health",
            headers={"Origin": "http://example.com"},
            timeout=5
        )
        self.assertIn("Access-Control-Allow-Origin", r.headers)


if __name__ == "__main__":
    print("=" * 60)
    print("  Hole Test: alice-facade-web")
    print(f"  Server: {WEB_BASE}")
    status = "🟩 GREEN" if _SERVER_OK else "🟥 RED (server not running)"
    print(f"  Status: {status}")
    print("=" * 60)
    unittest.main(verbosity=2)
