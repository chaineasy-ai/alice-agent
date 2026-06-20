#!/usr/bin/env python3
"""
Hole Test — alice-model module endpoints.

Each probe invokes ModelHoleTest directly via Gradle JavaExec (runHoleTest),
exercising module boundary without going through unit test runners.

See:
  docs/alice-agent-command/e2e/case-model.md
  docs/alice-model/e2e/scene-model-endpoints.md
"""

import os
import subprocess
import sys
import unittest

# Project root detection
PROJECT_ROOT = os.path.normpath(
    os.path.join(os.path.dirname(__file__), "..", "..", "..")
)

MODULE = "alice-model"


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


class TestModelHoles(unittest.TestCase):
    """Hole tests for alice-model — 5 probes via Java ModelHoleTest."""

    def test_mdl_p01_provider_dispatch(self):
        """MDL-P01: ModelProvider.dispatch() with FakeSupplier."""
        result = run_hole("dispatch")
        self.assertEqual(
            result.returncode, 0,
            msg=f"MDL-P01 failed: {result.stdout[-200:]}")
        self.assertIn(
            "PASS:", result.stdout,
            msg=f"MDL-P01: unexpected output: {result.stdout[-200:]}")

    def test_mdl_p02_call_lifecycle(self):
        """MDL-P02: Call.execute() lifecycle NEW→PENDING→RUNNING→FINISHED."""
        result = run_hole("call")
        self.assertEqual(
            result.returncode, 0,
            msg=f"MDL-P02 failed: {result.stdout[-200:]}")
        self.assertIn(
            "PASS:", result.stdout,
            msg=f"MDL-P02: unexpected output: {result.stdout[-200:]}")

    def test_mdl_p03_supplier_parse(self):
        """MDL-P03: ModelSupplier request/response."""
        result = run_hole("supplier")
        self.assertEqual(
            result.returncode, 0,
            msg=f"MDL-P03 failed: {result.stdout[-200:]}")
        self.assertIn(
            "PASS:", result.stdout,
            msg=f"MDL-P03: unexpected output: {result.stdout[-200:]}")

    def test_mdl_p04_config_loader(self):
        """MDL-P04: ModelConfigLoader.loadConfig() from JSON."""
        result = run_hole("config")
        self.assertEqual(
            result.returncode, 0,
            msg=f"MDL-P04 failed: {result.stdout[-200:]}")
        self.assertIn(
            "PASS:", result.stdout,
            msg=f"MDL-P04: unexpected output: {result.stdout[-200:]}")

    def test_mdl_p05_multi_routing(self):
        """MDL-P05: Multi-supplier routing by modelId."""
        result = run_hole("multi")
        self.assertEqual(
            result.returncode, 0,
            msg=f"MDL-P05 failed: {result.stdout[-200:]}")
        self.assertIn(
            "PASS:", result.stdout,
            msg=f"MDL-P05: unexpected output: {result.stdout[-200:]}")


if __name__ == "__main__":
    print("=" * 60)
    print(f"  Hole Test: {MODULE}")
    print(f"  Module: {os.path.join(PROJECT_ROOT, MODULE)}")
    print("=" * 60)
    print(f"  Holes: MDL-P01..P05 (5 probes)")
    print(f"  Prober: ModelHoleTest (Java Exec)")
    print("=" * 60)
    unittest.main(verbosity=2)
