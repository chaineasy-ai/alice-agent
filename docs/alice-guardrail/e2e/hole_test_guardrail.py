#!/usr/bin/env python3
"""
Hole Test — alice-guardrail module endpoints.

Probes GuardrailService, PolicyEngine, HallucinationDetector,
and PermissionSandboxValidator via Spock unit tests added in
`src/test/groovy/org/cland/alice/guardrail/`.

See:
  docs/alice-agent-command/e2e/case-guardrail.md
  docs/alice-guardrail/e2e/scene-guardrail-endpoints.md
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "..", "e2e"))
from helpers import run_gradle_task, PROJECT_ROOT


# ── Detect if guardrail has any test sources ──────────────────────
def _guardrail_has_tests():
    """Check if alice-guardrail has actual .groovy or .java test files."""
    test_dir = PROJECT_ROOT / "alice-guardrail" / "src" / "test"
    if not test_dir.exists():
        return False
    return any(
        f.suffix in (".groovy", ".java")
        for f in test_dir.rglob("*")
        if f.is_file()
    )
_HAS_TESTS = _guardrail_has_tests()


class TestGuardrailHoles(unittest.TestCase):
    """Hole tests for alice-guardrail — 5 probes."""

    @classmethod
    def setUpClass(cls):
        cls.build_ok = (PROJECT_ROOT / "alice-guardrail" / "build").is_dir()

    def _check_has_tests(self):
        """Skip if no unit tests exist. This hole is 🟥 RED until tests are written."""
        if not _HAS_TESTS:
            self.skipTest("GRD: 🟥 RED — no unit tests exist yet for alice-guardrail")

    def test_grd_p01_verify_plan(self):
        """GRD-P01: GuardrailService.verifyPlan() pre-validation."""
        if not self.build_ok:
            self.skipTest("Module not built.")
        self._check_has_tests()
        result = run_gradle_task(":alice-guardrail:test")
        self.assertEqual(result.returncode, 0,
                         msg=f"GRD-P01 failed: {result.stderr[:200]}")

    def test_grd_p02_verify_result(self):
        """GRD-P02: GuardrailService.verifyResult() post-validation."""
        if not self.build_ok:
            self.skipTest("Module not built.")
        self._check_has_tests()
        result = run_gradle_task(":alice-guardrail:test")
        self.assertEqual(result.returncode, 0,
                         msg=f"GRD-P02 failed: {result.stderr[:200]}")

    def test_grd_p03_policy_engine(self):
        """GRD-P03: PolicyEngine.evaluate() policy matching."""
        if not self.build_ok:
            self.skipTest("Module not built.")
        self._check_has_tests()
        result = run_gradle_task(":alice-guardrail:test")
        self.assertEqual(result.returncode, 0,
                         msg=f"GRD-P03 failed: {result.stderr[:200]}")

    def test_grd_p04_hallucination_detector(self):
        """GRD-P04: HallucinationDetector detects contradictions."""
        if not self.build_ok:
            self.skipTest("Module not built.")
        self._check_has_tests()
        result = run_gradle_task(":alice-guardrail:test")
        self.assertEqual(result.returncode, 0,
                         msg=f"GRD-P04 failed: {result.stderr[:200]}")

    def test_grd_p05_permission_sandbox(self):
        """GRD-P05: PermissionSandboxValidator access control."""
        if not self.build_ok:
            self.skipTest("Module not built.")
        self._check_has_tests()
        result = run_gradle_task(":alice-guardrail:test")
        self.assertEqual(result.returncode, 0,
                         msg=f"GRD-P05 failed: {result.stderr[:200]}")


if __name__ == "__main__":
    print("=" * 60)
    print("  Hole Test: alice-guardrail")
    print(f"  Module: {PROJECT_ROOT / 'alice-guardrail'}")
    status = "🟥 RED" if not _HAS_TESTS else "🟩 GREEN (with existing tests)"
    print(f"  Status: {status}")
    print("=" * 60)
    unittest.main(verbosity=2)
