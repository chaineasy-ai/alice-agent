#!/usr/bin/env python3
"""
Hole Test — alice-core-planner module endpoints.

Each probe invokes PlannerHoleTest directly via Gradle JavaExec (runHoleTest),
exercising module boundary without going through unit test runners.

See:
  docs/alice-agent-command/e2e/case-core-planner.md
  docs/alice-core-planner/e2e/scene-planner-endpoints.md
  docs/alice-core-planner/inbound.md
"""

import os
import subprocess
import sys
import unittest

# Project root detection
PROJECT_ROOT = os.path.normpath(
    os.path.join(os.path.dirname(__file__), "..", "..", "..")
)

MODULE = "alice-core-planner"


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


class TestPlannerHoles(unittest.TestCase):
    """Hole tests for alice-core-planner — 7 probes via Java PlannerHoleTest."""

    def test_pln_p01_planner_service_plan(self):
        """PLN-P01: PlannerService.plan(Map) returns Plan for simple prompt."""
        result = run_hole("service")
        self.assertEqual(
            result.returncode, 0,
            msg=f"PLN-P01 failed: {result.stdout[-200:]}")
        self.assertIn(
            "PASS:", result.stdout,
            msg=f"PLN-P01: unexpected output: {result.stdout[-200:]}")

    def test_pln_p02_fast_path_strategy(self):
        """PLN-P02: FastPathStrategy.decide() returns FAST_PATH Plan."""
        result = run_hole("fast_path")
        self.assertEqual(
            result.returncode, 0,
            msg=f"PLN-P02 failed: {result.stdout[-200:]}")
        self.assertIn(
            "PASS:", result.stdout,
            msg=f"PLN-P02: unexpected output: {result.stdout[-200:]}")

    def test_pln_p03_slow_path_strategy(self):
        """PLN-P03: SlowPathStrategy.decide() returns SLOW_PATH Plan with tree metadata."""
        result = run_hole("slow_path")
        self.assertEqual(
            result.returncode, 0,
            msg=f"PLN-P03 failed: {result.stdout[-200:]}")
        self.assertIn(
            "PASS:", result.stdout,
            msg=f"PLN-P03: unexpected output: {result.stdout[-200:]}")

    def test_pln_p04_strategy_selector(self):
        """PLN-P04: StrategySelector.select() routes simple→fast, complex→slow."""
        result = run_hole("selector")
        self.assertEqual(
            result.returncode, 0,
            msg=f"PLN-P04 failed: {result.stdout[-200:]}")
        self.assertIn(
            "PASS:", result.stdout,
            msg=f"PLN-P04: unexpected output: {result.stdout[-200:]}")

    def test_pln_p05_token_budget(self):
        """PLN-P05: TokenBudget enforces limits and tracks consumption."""
        result = run_hole("budget")
        self.assertEqual(
            result.returncode, 0,
            msg=f"PLN-P05 failed: {result.stdout[-200:]}")
        self.assertIn(
            "PASS:", result.stdout,
            msg=f"PLN-P05: unexpected output: {result.stdout[-200:]}")

    def test_pln_p06_thinking_tree(self):
        """PLN-P06: ThinkingTree expand/backpropagate/bestPath work correctly."""
        result = run_hole("tree")
        self.assertEqual(
            result.returncode, 0,
            msg=f"PLN-P06 failed: {result.stdout[-200:]}")
        self.assertIn(
            "PASS:", result.stdout,
            msg=f"PLN-P06: unexpected output: {result.stdout[-200:]}")

    def test_pln_p07_static_planner(self):
        """PLN-P07: StaticPlanner + SopRegistry returns STATIC Plan from SOP."""
        result = run_hole("static_planner")
        self.assertEqual(
            result.returncode, 0,
            msg=f"PLN-P07 failed: {result.stdout[-200:]}")
        self.assertIn(
            "PASS:", result.stdout,
            msg=f"PLN-P07: unexpected output: {result.stdout[-200:]}")


if __name__ == "__main__":
    print("=" * 60)
    print(f"  Hole Test: {MODULE}")
    print(f"  Module: {os.path.join(PROJECT_ROOT, MODULE)}")
    print("=" * 60)
    print(f"  Holes: PLN-P01..P07 (7 probes)")
    print(f"  Prober: PlannerHoleTest (Java Exec)")
    print("=" * 60)
    unittest.main(verbosity=2)
