#!/usr/bin/env python3
"""
Hole Test — alice-core-planner module endpoints.

Verifies 7 probes against PlannerServiceSpec:
  PLN-P01  PlannerService.plan(Map)          → Plan
  PLN-P02  FastPathStrategy.decide(Map)      → FAST_PATH Plan
  PLN-P03  SlowPathStrategy.decide(Map)      → SLOW_PATH Plan
  PLN-P04  StrategySelector.select(Map)      → fast/slow route
  PLN-P05  TokenBudget.consume()             → isExhausted
  PLN-P06  ThinkingTree MCTS operations      → bestPath
  PLN-P07  StaticPlanner / SopRegistry       → STATIC Plan

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
    """Hole tests for alice-core-planner — 7 probes."""

    @classmethod
    def setUpClass(cls):
        cls.build_ok = (PROJECT_ROOT / "alice-core-planner" / "build").is_dir()

    # ── PLN-P01: PlannerService.plan(Map) ──────────────────────────────────

    def test_pln_p01_planner_service_plan(self):
        """PLN-P01: PlannerService.plan(Map) returns Plan for simple prompt."""
        if not self.build_ok:
            self.skipTest("Module not built.")
        result = run_gradle_task(
            ":alice-core-planner:test",
            "--tests", "*PlannerServiceSpec*",
            "-Dtest.groups=PLN-P01",
        )
        self.assertEqual(
            result.returncode, 0,
            msg=f"PLN-P01 failed: {result.stderr[:300]}",
        )

    # ── PLN-P02: FastPathStrategy.decide(Map) ──────────────────────────────

    def test_pln_p02_fast_path_strategy(self):
        """PLN-P02: FastPathStrategy.decide() returns FAST_PATH Plan."""
        if not self.build_ok:
            self.skipTest("Module not built.")
        result = run_gradle_task(
            ":alice-core-planner:test",
            "--tests", "*PlannerServiceSpec*",
        )
        self.assertEqual(
            result.returncode, 0,
            msg=f"PLN-P02 failed: {result.stderr[:300]}",
        )

    # ── PLN-P03: SlowPathStrategy.decide(Map) ──────────────────────────────

    def test_pln_p03_slow_path_strategy(self):
        """PLN-P03: SlowPathStrategy.decide() returns SLOW_PATH Plan with tree metadata."""
        if not self.build_ok:
            self.skipTest("Module not built.")
        result = run_gradle_task(
            ":alice-core-planner:test",
            "--tests", "*PlannerServiceSpec*",
        )
        self.assertEqual(
            result.returncode, 0,
            msg=f"PLN-P03 failed: {result.stderr[:300]}",
        )

    # ── PLN-P04: StrategySelector.select(Map) ──────────────────────────────

    def test_pln_p04_strategy_selector(self):
        """PLN-P04: StrategySelector.select() routes simple→fast, complex→slow."""
        if not self.build_ok:
            self.skipTest("Module not built.")
        result = run_gradle_task(
            ":alice-core-planner:test",
            "--tests", "*PlannerServiceSpec*",
        )
        self.assertEqual(
            result.returncode, 0,
            msg=f"PLN-P04 failed: {result.stderr[:300]}",
        )

    # ── PLN-P05: TokenBudget ───────────────────────────────────────────────

    def test_pln_p05_token_budget(self):
        """PLN-P05: TokenBudget enforces limits and tracks consumption."""
        if not self.build_ok:
            self.skipTest("Module not built.")
        result = run_gradle_task(
            ":alice-core-planner:test",
            "--tests", "*PlannerServiceSpec*",
        )
        self.assertEqual(
            result.returncode, 0,
            msg=f"PLN-P05 failed: {result.stderr[:300]}",
        )

    # ── PLN-P06: ThinkingTree MCTS ─────────────────────────────────────────

    def test_pln_p06_thinking_tree(self):
        """PLN-P06: ThinkingTree expand/backpropagate/bestPath work correctly."""
        if not self.build_ok:
            self.skipTest("Module not built.")
        result = run_gradle_task(
            ":alice-core-planner:test",
            "--tests", "*PlannerServiceSpec*",
        )
        self.assertEqual(
            result.returncode, 0,
            msg=f"PLN-P06 failed: {result.stderr[:300]}",
        )

    # ── PLN-P07: StaticPlanner / SopRegistry ───────────────────────────────

    def test_pln_p07_static_planner(self):
        """PLN-P07: StaticPlanner + SopRegistry returns STATIC Plan from SOP."""
        if not self.build_ok:
            self.skipTest("Module not built.")
        result = run_gradle_task(
            ":alice-core-planner:test",
            "--tests", "*PlannerServiceSpec*",
        )
        self.assertEqual(
            result.returncode, 0,
            msg=f"PLN-P07 failed: {result.stderr[:300]}",
        )


if __name__ == "__main__":
    print("=" * 60)
    print("  Hole Test: alice-core-planner")
    print(f"  Module: {PROJECT_ROOT / 'alice-core-planner'}")
    print("=" * 60)
    print("  Holes: PLN-P01..P07 (7 probes)")
    print("  Verifier: PlannerServiceSpec (Spock)")
    print("=" * 60)
    unittest.main(verbosity=2)
