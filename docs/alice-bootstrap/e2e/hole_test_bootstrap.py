#!/usr/bin/env python3
"""
Hole Test — alice-bootstrap module endpoints.

Each probe invokes BootstrapHoleTest directly via Gradle JavaExec (runHoleTest),
exercising module boundary without going through unit test runners.

See:
  docs/alice-agent-command/e2e/case-bootstrap.md
  docs/alice-bootstrap/e2e/scene-bootstrap-endpoints.md
"""

import os
import subprocess
import sys
import unittest

# Project root detection
PROJECT_ROOT = os.path.normpath(
    os.path.join(os.path.dirname(__file__), "..", "..", "..")
)

MODULE = "alice-bootstrap"


def run_hole(key: str, timeout: int = 30) -> "subprocess.CompletedProcess":
    """Run a single hole test probe via Gradle runHoleTest task."""
    cmd = [
        "cmd", "/c",
        "gradlew.bat",
        f":{MODULE}:runHoleTest",
        f"--args={key}",
    ]
    result = subprocess.run(
        cmd,
        cwd=PROJECT_ROOT,
        capture_output=True,
        text=True,
        timeout=timeout,
    )
    return result


class TestBootstrapHoles(unittest.TestCase):
    """Hole tests for alice-bootstrap — 3 probes via Java BootstrapHoleTest."""

    def test_bts_p01_facade_selector(self):
        """BTS-P01: FacadeSelector.launch() routing."""
        result = run_hole("facadeSelector")
        self.assertEqual(
            result.returncode, 0,
            msg=f"BTS-P01 failed: {result.stderr[-200:]}")
        self.assertIn(
            "PASS:", result.stdout,
            msg=f"BTS-P01: unexpected output: {result.stdout[-200:]}")

    def test_bts_p02_alice_app(self):
        """BTS-P02: AliceApp class and main() method."""
        result = run_hole("aliceApp")
        self.assertEqual(
            result.returncode, 0,
            msg=f"BTS-P02 failed: {result.stderr[-200:]}")
        self.assertIn(
            "PASS:", result.stdout,
            msg=f"BTS-P02: unexpected output: {result.stdout[-200:]}")

    def test_bts_p03_facade_contract(self):
        """BTS-P03: AliceFacade SPI interface contract."""
        result = run_hole("facadeContract")
        self.assertEqual(
            result.returncode, 0,
            msg=f"BTS-P03 failed: {result.stderr[-200:]}")
        self.assertIn(
            "PASS:", result.stdout,
            msg=f"BTS-P03: unexpected output: {result.stdout[-200:]}")


if __name__ == "__main__":
    print("=" * 60)
    print(f"  Hole Test: {MODULE}")
    print(f"  Module: {os.path.join(PROJECT_ROOT, MODULE)}")
    print("=" * 60)
    print(f"  Holes: BTS-P01..P03 (3 probes)")
    print(f"  Prober: BootstrapHoleTest (Java Exec)")
    print("=" * 60)
    unittest.main(verbosity=2)
