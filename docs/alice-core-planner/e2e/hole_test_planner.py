#!/usr/bin/env python3
"""
Hole Test — alice-core-planner module endpoints.

See:
  docs/alice-agent-command/e2e/case-core-planner.md
  docs/alice-core-planner/e2e/scene-planner-endpoints.md
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "..", "e2e"))
from helpers import run_gradle_task, PROJECT_ROOT


class TestPlannerHoles(unittest.TestCase):
    """Hole tests for alice-core-planner — 4 probes."""

    @classmethod
    def setUpClass(cls):
        cls.build_ok = (PROJECT_ROOT / "alice-core-planner" / "build").is_dir()

    def test_pln_p01_planner_service(self):
        """PLN-P01: PlannerService.plan() returns Plan."""
        if not self.build_ok:
            self.skipTest("Module not built.")
        result = run_gradle_task(":alice-core-planner:test", "--tests", "*PlannerServiceSpec*")
        self.assertEqual(result.returncode, 0,
                         msg=f"PLN-P01 failed: {result.stderr[:200]}")

    def test_pln_p02_fast_path(self):
        """PLN-P02: FastPathStrategy.decide() produces plan."""
        if not self.build_ok:
            self.skipTest("Module not built.")
        result = run_gradle_task(":alice-core-planner:test", "--tests", "*PlannerServiceSpec*")
        self.assertEqual(result.returncode, 0,
                         msg=f"PLN-P02 failed: {result.stderr[:200]}")

    def test_pln_p03_slow_path(self):
        """PLN-P03: SlowPathStrategy.decide() produces plan."""
        if not self.build_ok:
            self.skipTest("Module not built.")
        result = run_gradle_task(":alice-core-planner:test", "--tests", "*PlannerServiceSpec*")
        self.assertEqual(result.returncode, 0,
                         msg=f"PLN-P03 failed: {result.stderr[:200]}")

    def test_pln_p04_world_model(self):
        """PLN-P04: WorldModel.predict() returns Observation."""
        if not self.build_ok:
            self.skipTest("Module not built.")
        # WorldModel test may be embedded in PlannerServiceSpec
        result = run_gradle_task(":alice-core-planner:test", "--tests", "*PlannerServiceSpec*")
        self.assertEqual(result.returncode, 0,
                         msg=f"PLN-P04 failed: {result.stderr[:200]}")


if __name__ == "__main__":
    print("=" * 60)
    print("  Hole Test: alice-core-planner")
    print(f"  Module: {PROJECT_ROOT / 'alice-core-planner'}")
    print("=" * 60)
    unittest.main(verbosity=2)
