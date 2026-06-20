#!/usr/bin/env python3
"""
Hole Test — alice-guardrail module endpoints.

Each probe invokes GuardrailHoleTest directly via Gradle JavaExec (runHoleTest),
exercising module boundary without going through unit test runners.

See:
  docs/alice-agent-command/e2e/case-guardrail.md
  docs/alice-guardrail/e2e/scene-guardrail-endpoints.md
"""

import os
import subprocess
import sys
import unittest

# Project root detection
PROJECT_ROOT = os.path.normpath(
    os.path.join(os.path.dirname(__file__), "..", "..", "..")
)

MODULE = "alice-guardrail"


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


class TestGuardrailHoles(unittest.TestCase):
    """Hole tests for alice-guardrail — 5 probes via Java GuardrailHoleTest."""

    def test_grd_p01_verify_plan(self):
        """GRD-P01: GuardrailService.verifyPlan() pre-validation."""
        result = run_hole("verifyPlan")
        self.assertEqual(
            result.returncode, 0,
            msg=f"GRD-P01 failed: {result.stdout[-200:]}")
        self.assertIn(
            "PASS:", result.stdout,
            msg=f"GRD-P01: unexpected output: {result.stdout[-200:]}")

    def test_grd_p02_verify_result(self):
        """GRD-P02: GuardrailService.verifyResult() post-validation."""
        result = run_hole("verifyResult")
        self.assertEqual(
            result.returncode, 0,
            msg=f"GRD-P02 failed: {result.stdout[-200:]}")
        self.assertIn(
            "PASS:", result.stdout,
            msg=f"GRD-P02: unexpected output: {result.stdout[-200:]}")

    def test_grd_p03_policy_engine(self):
        """GRD-P03: PolicyEngine evaluate + schema validation + safety filter."""
        result = run_hole("policyEngine")
        self.assertEqual(
            result.returncode, 0,
            msg=f"GRD-P03 failed: {result.stdout[-200:]}")
        self.assertIn(
            "PASS:", result.stdout,
            msg=f"GRD-P03: unexpected output: {result.stdout[-200:]}")

    def test_grd_p04_hallucination_detector(self):
        """GRD-P04: HallucinationDetector detects contradictions."""
        result = run_hole("hallucinate")
        self.assertEqual(
            result.returncode, 0,
            msg=f"GRD-P04 failed: {result.stdout[-200:]}")
        self.assertIn(
            "PASS:", result.stdout,
            msg=f"GRD-P04: unexpected output: {result.stdout[-200:]}")

    def test_grd_p05_permission_sandbox(self):
        """GRD-P05: PermissionSandboxValidator access control."""
        result = run_hole("sandbox")
        self.assertEqual(
            result.returncode, 0,
            msg=f"GRD-P05 failed: {result.stdout[-200:]}")
        self.assertIn(
            "PASS:", result.stdout,
            msg=f"GRD-P05: unexpected output: {result.stdout[-200:]}")


if __name__ == "__main__":
    print("=" * 60)
    print(f"  Hole Test: {MODULE}")
    print(f"  Module: {os.path.join(PROJECT_ROOT, MODULE)}")
    print("=" * 60)
    print(f"  Holes: GRD-P01..P05 (5 probes)")
    print(f"  Prober: GuardrailHoleTest (Java Exec)")
    print("=" * 60)
    unittest.main(verbosity=2)
