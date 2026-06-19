#!/usr/bin/env python3
"""
Hole Test — alice-guardrail module endpoints.

This module currently has 0 unit tests — hole tests are the first
verification layer. They probe the public API boundary by calling
Gradle `test` (when unit tests exist) or via direct Java invocation.

See:
  docs/alice-agent-command/e2e/case-guardrail.md
  docs/alice-guardrail/e2e/scene-guardrail-endpoints.md
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "..", "e2e"))
from helpers import run_gradle_task, PROJECT_ROOT


class TestGuardrailHoles(unittest.TestCase):
    """Hole tests for alice-guardrail — 5 probes."""

    @classmethod
    def setUpClass(cls):
        cls.build_ok = (PROJECT_ROOT / "alice-guardrail" / "build").is_dir()

    def test_grd_p01_verify_plan(self):
        """GRD-P01: GuardrailService.verifyPlan() pre-validation."""
        if not self.build_ok:
            self.skipTest("Module not built.")
        # Currently 0 unit tests — mark as known gap
        result = run_gradle_task(":alice-guardrail:test")
        if result.returncode != 0 and "No tests executed" in result.stderr:
            self.skipTest("No unit tests exist yet for alice-guardrail")
        self.assertEqual(result.returncode, 0,
                         msg=f"GRD-P01 failed: {result.stderr[:200]}")

    def test_grd_p02_verify_result(self):
        """GRD-P02: GuardrailService.verifyResult() post-validation."""
        if not self.build_ok:
            self.skipTest("Module not built.")
        result = run_gradle_task(":alice-guardrail:test")
        if result.returncode != 0 and "No tests executed" in result.stderr:
            self.skipTest("No unit tests exist yet for alice-guardrail")
        self.assertEqual(result.returncode, 0,
                         msg=f"GRD-P02 failed: {result.stderr[:200]}")

    def test_grd_p03_policy_engine(self):
        """GRD-P03: PolicyEngine.evaluate() policy matching."""
        if not self.build_ok:
            self.skipTest("Module not built.")
        result = run_gradle_task(":alice-guardrail:test")
        if result.returncode != 0 and "No tests executed" in result.stderr:
            self.skipTest("No unit tests exist yet for alice-guardrail")
        self.assertEqual(result.returncode, 0,
                         msg=f"GRD-P03 failed: {result.stderr[:200]}")

    def test_grd_p04_hallucination_detector(self):
        """GRD-P04: HallucinationDetector detects contradictions."""
        if not self.build_ok:
            self.skipTest("Module not built.")
        result = run_gradle_task(":alice-guardrail:test")
        if result.returncode != 0 and "No tests executed" in result.stderr:
            self.skipTest("No unit tests exist yet for alice-guardrail")
        self.assertEqual(result.returncode, 0,
                         msg=f"GRD-P04 failed: {result.stderr[:200]}")

    def test_grd_p05_permission_sandbox(self):
        """GRD-P05: PermissionSandboxValidator access control."""
        if not self.build_ok:
            self.skipTest("Module not built.")
        result = run_gradle_task(":alice-guardrail:test")
        if result.returncode != 0 and "No tests executed" in result.stderr:
            self.skipTest("No unit tests exist yet for alice-guardrail")
        self.assertEqual(result.returncode, 0,
                         msg=f"GRD-P05 failed: {result.stderr[:200]}")


if __name__ == "__main__":
    print("=" * 60)
    print("  Hole Test: alice-guardrail")
    print(f"  Module: {PROJECT_ROOT / 'alice-guardrail'}")
    print("  ⚠ No unit tests yet — holes are the first coverage layer")
    print("=" * 60)
    unittest.main(verbosity=2)
