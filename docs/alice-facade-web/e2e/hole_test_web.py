#!/usr/bin/env python3
"""
Hole Test — alice-facade-web HTTP endpoints.

Requires the web server to be running on a known port.

See:
  docs/alice-agent-command/e2e/case-web.md
  docs/alice-facade-web/e2e/scene-web-endpoints.md
"""

import os
import sys
import unittest
import requests

WEB_PORT = os.environ.get("ALICE_WEB_PORT", "8080")
WEB_BASE = f"http://localhost:{WEB_PORT}"


class TestWebHoles(unittest.TestCase):
    """Hole tests for alice-facade-web — 3 probes."""

    @classmethod
    def setUpClass(cls):
        # Check if server is reachable
        try:
            r = requests.get(f"{WEB_BASE}/health", timeout=3)
            cls.server_ok = True
        except (requests.ConnectionError, requests.Timeout):
            cls.server_ok = False

    def test_web_p01_health_check(self):
        """WEB-P01: GET /health returns 200."""
        if not self.server_ok:
            self.skipTest("Web server not reachable")
        r = requests.get(f"{WEB_BASE}/health", timeout=5)
        self.assertEqual(r.status_code, 200)
        self.assertIn("UP", r.text)

    def test_web_p02_not_found(self):
        """WEB-P02: Unknown path returns 404."""
        if not self.server_ok:
            self.skipTest("Web server not reachable")
        r = requests.get(f"{WEB_BASE}/nonexistent", timeout=5)
        self.assertEqual(r.status_code, 404)

    def test_web_p03_cors(self):
        """WEB-P03: OPTIONS /health includes CORS headers."""
        if not self.server_ok:
            self.skipTest("Web server not reachable")
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
    print("=" * 60)
    unittest.main(verbosity=2)
