#!/usr/bin/env python3
"""
Hole Test — alice-core-agent module endpoints.

These are hole_test: module boundary probes that verify public API
entry points work correctly. Not E2E, not unit — just holes.

See:
  docs/alice-agent-command/e2e/case-core-agent.md
  docs/alice-core-agent/e2e/scene-executor-endpoints.md
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "..", "e2e"))
from helpers import run_gradle_task, PROJECT_ROOT


class TestCoreAgentHoles(unittest.TestCase):
    """Hole tests for alice-core-agent — 4 probes."""

    @classmethod
    def setUpClass(cls):
        cls.build_ok = (PROJECT_ROOT / "alice-core-agent" / "build").is_dir()

    # ── AGT-P01: AgentExecutor.execute() happy path ───────────────────

    def test_agt_p01_executor_execute(self):
        """AGT-P01: AgentExecutor.execute(Input) returns StepResult."""
        # This probe runs the existing AgentPpaoLoopSpec which exercises
        # the full execute path via Spock. If it passes, the hole is open.
        if not self.build_ok:
            self.skipTest("Module not built. Run build first.")
        result = run_gradle_task(":alice-core-agent:test", "--tests", "*AgentPpaoLoopSpec*")
        self.assertEqual(result.returncode, 0,
                         msg=f"AGT-P01 failed: {result.stderr[:200]}")

    # ── AGT-P02: StepResult sealed pattern match ─────────────────────

    def test_agt_p02_step_result_sealed(self):
        """AGT-P02: StepResult sealed hierarchy complete."""
        if not self.build_ok:
            self.skipTest("Module not built. Run build first.")
        result = run_gradle_task(":alice-core-agent:test", "--tests", "*StepResultSpec*")
        self.assertEqual(result.returncode, 0,
                         msg=f"AGT-P02 failed: {result.stderr[:200]}")

    # ── AGT-P03: AgentContext session lifecycle ─────────────────────

    def test_agt_p03_context_lifecycle(self):
        """AGT-P03: AgentContext session lifecycle works."""
        if not self.build_ok:
            self.skipTest("Module not built. Run build first.")
        result = run_gradle_task(":alice-core-agent:test", "--tests", "*AgentContextSpec*")
        self.assertEqual(result.returncode, 0,
                         msg=f"AGT-P03 failed: {result.stderr[:200]}")

    # ── AGT-P05: SubAgentManager register/list/lookup ────────────────

    def test_agt_p05_subagent_manager(self):
        """AGT-P05: SubAgentManager register/list/lookup/unregister."""
        if not self.build_ok:
            self.skipTest("Module not built. Run build first.")
        result = run_gradle_task(":alice-core-agent:test", "--tests", "*SubAgentManagerSpec*")
        self.assertEqual(result.returncode, 0,
                         msg=f"AGT-P05 failed: {result.stderr[:200]}")


if __name__ == "__main__":
    print("=" * 60)
    print("  Hole Test: alice-core-agent")
    print(f"  Module: {PROJECT_ROOT / 'alice-core-agent'}")
    print("=" * 60)
    unittest.main(verbosity=2)
