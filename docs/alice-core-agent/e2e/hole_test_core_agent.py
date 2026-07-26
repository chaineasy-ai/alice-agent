#!/usr/bin/env python3
"""
Hole Test — alice-core-agent module endpoints.

Each probe invokes CoreAgentHoleTest directly via Gradle JavaExec (runHoleTest),
exercising module boundary without going through unit test runners.

See:
  docs/alice-agent-command/e2e/case-core-agent.md
  docs/alice-core-agent/e2e/scene-core-agent-endpoints.md
"""

import os
import sys
import unittest

PROJECT_ROOT = os.path.normpath(
    os.path.join(os.path.dirname(__file__), "..", "..", "..")
)

MODULE = "alice-core-agent"

# Insert project root so we can import e2e/helpers
sys.path.insert(0, os.path.join(PROJECT_ROOT, "e2e"))
from helpers import run_gradle_task


def run_hole(key: str, timeout: int = 60) -> "subprocess.CompletedProcess":
    """Run a CoreAgentHoleTest probe via Gradle."""
    return run_gradle_task(
        f":{MODULE}:runHoleTest", f"--args={key}", timeout=timeout
    )


class TestCoreAgentHoles(unittest.TestCase):
    """Hole tests for alice-core-agent — 5 probes via Java CoreAgentHoleTest."""

    def test_agt_p01_agent_context(self):
        """AGT-P01: AgentContext session lifecycle."""
        result = run_hole("context")
        self.assertEqual(
            result.returncode, 0,
            msg=f"AGT-P01 failed: {result.stdout[-200:]}")
        self.assertIn(
            "PASS:", result.stdout,
            msg=f"AGT-P01: unexpected output: {result.stdout[-200:]}")

    def test_agt_p02_step_result(self):
        """AGT-P02: StepResult sealed pattern match."""
        result = run_hole("stepResult")
        self.assertEqual(
            result.returncode, 0,
            msg=f"AGT-P02 failed: {result.stdout[-200:]}")
        self.assertIn(
            "PASS:", result.stdout,
            msg=f"AGT-P02: unexpected output: {result.stdout[-200:]}")

    def test_agt_p03_action_builder(self):
        """AGT-P03: Action builder and static factories."""
        result = run_hole("action")
        self.assertEqual(
            result.returncode, 0,
            msg=f"AGT-P03 failed: {result.stdout[-200:]}")
        self.assertIn(
            "PASS:", result.stdout,
            msg=f"AGT-P03: unexpected output: {result.stdout[-200:]}")

    def test_agt_p04_agent_executor(self):
        """AGT-P04: AgentExecutor class loads."""
        result = run_hole("executor")
        self.assertEqual(
            result.returncode, 0,
            msg=f"AGT-P04 failed: {result.stdout[-200:]}")
        self.assertIn(
            "PASS:", result.stdout,
            msg=f"AGT-P04: unexpected output: {result.stdout[-200:]}")

    def test_agt_p05_intent_composite(self):
        """AGT-P05: Intent composite with model routing."""
        result = run_hole("intent")
        self.assertEqual(
            result.returncode, 0,
            msg=f"AGT-P05 failed: {result.stdout[-200:]}")
        self.assertIn(
            "PASS:", result.stdout,
            msg=f"AGT-P05: unexpected output: {result.stdout[-200:]}")


if __name__ == "__main__":
    print("=" * 60)
    print(f"  Hole Test: {MODULE}")
    print(f"  Module: {os.path.join(PROJECT_ROOT, MODULE)}")
    print("=" * 60)
    print(f"  Holes: AGT-P01..P05 (5 probes)")
    print(f"  Prober: CoreAgentHoleTest (Java Exec)")
    print("=" * 60)
    unittest.main(verbosity=2)
