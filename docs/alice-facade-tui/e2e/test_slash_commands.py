#!/usr/bin/env python3
"""
E2E Test — TUI Slash Commands.

See: docs/alice-agent-command/e2e/case-tui-slash-commands.md
     docs/alice-facade-tui/e2e/scene-tui-slash-commands.md

TDD: Case doc → test → pass
Note: All tests require JLine terminal — documented skips with unit test cross-references.
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "..", "e2e"))
from helpers import PROJECT_ROOT


@unittest.skipIf(
    not (PROJECT_ROOT / "alice-bootstrap" / "build").is_dir(),
    "Project not built yet. Run with --build first."
)
class TestAliceSlashCommands(unittest.TestCase):
    """E2E tests for TUI slash commands — 30 cases (all documented skips)."""

    maxDiff = None

    # ── Natural Language ──────────────────────────────────────────────

    def test_slash_01_natural_language(self):
        """TC-SLASH-01: Natural language → AcquireGoalCmd."""
        self.skipTest("JLine terminal required. Verified by AgentCommandParseSpec.groovy")

    # ── /run ───────────────────────────────────────────────────────────

    def test_slash_02_run_with_goal(self):
        """TC-SLASH-02: /run <goal> → AcquireGoalCmd."""
        self.skipTest("JLine terminal required. Verified by AgentCommandParseSpec.groovy")

    def test_slash_03_run_no_args(self):
        """TC-SLASH-03: /run (empty) → AcquireGoalCmd."""
        self.skipTest("JLine terminal required. Verified by AgentCommandParseSpec.groovy")

    # ── /exec ──────────────────────────────────────────────────────────

    def test_slash_04_exec_with_command(self):
        """TC-SLASH-04: /exec <cmd> → ExecuteRawCmd."""
        self.skipTest("JLine terminal required. Verified by AgentCommandParseSpec.groovy")

    def test_slash_05_exec_no_args(self):
        """TC-SLASH-05: /exec (default) → ExecuteRawCmd."""
        self.skipTest("JLine terminal required. Verified by AgentCommandParseSpec.groovy")

    # ── Capability ─────────────────────────────────────────────────────

    def test_slash_06_skill(self):
        """TC-SLASH-06: /skill <ref> → RegisterSkillCmd."""
        self.skipTest("JLine terminal required. Verified by CapabilityCmdSpec.groovy")

    def test_slash_07_rules(self):
        """TC-SLASH-07: /rules <ref> → UpdateRulesCmd."""
        self.skipTest("JLine terminal required. Verified by CapabilityCmdSpec.groovy")

    def test_slash_08_reload(self):
        """TC-SLASH-08: /reload → ReloadKernelCmd."""
        self.skipTest("JLine terminal required. Verified by CapabilityCmdSpec.groovy")

    def test_slash_09_reload_extra(self):
        """TC-SLASH-09: /reload all (ignored args)."""
        self.skipTest("JLine terminal required. Verified by CapabilityCmdSpec.groovy")

    # ── Alignment ──────────────────────────────────────────────────────

    def test_slash_10_model_with_id(self):
        """TC-SLASH-10: /model <id> → SwitchModelCmd."""
        self.skipTest("JLine terminal required. Verified by AlignmentCmdSpec.groovy")

    def test_slash_11_model_no_args(self):
        """TC-SLASH-11: /model (default) → SwitchModelCmd."""
        self.skipTest("JLine terminal required. Verified by AlignmentCmdSpec.groovy")

    # ── Control ────────────────────────────────────────────────────────

    def test_slash_12_new(self):
        """TC-SLASH-12: /new → ResetSessionCmd."""
        self.skipTest("JLine terminal required. Verified by ControlCmdSpec.groovy")

    def test_slash_13_feedback(self):
        """TC-SLASH-13: /feedback <msg> → FeedbackCmd."""
        self.skipTest("JLine terminal required. Verified by ControlCmdSpec.groovy")

    def test_slash_14_exit(self):
        """TC-SLASH-14: /exit → InterruptCmd."""
        self.skipTest("JLine terminal required. Verified by ControlCmdSpec.groovy")

    def test_slash_15_clear(self):
        """TC-SLASH-15: /clear → ClearContextCmd."""
        self.skipTest("JLine terminal required. Verified by ControlCmdSpec.groovy")

    def test_slash_16_clear_extra(self):
        """TC-SLASH-16: /clear all (ignored args)."""
        self.skipTest("JLine terminal required. Verified by ControlCmdSpec.groovy")

    def test_slash_17_context(self):
        """TC-SLASH-17: /context → ViewContextCmd."""
        self.skipTest("JLine terminal required. Verified by ControlCmdSpec.groovy")

    def test_slash_18_compact(self):
        """TC-SLASH-18: /compact → CompactContextCmd."""
        self.skipTest("JLine terminal required. Verified by ControlCmdSpec.groovy")

    # ── Routine-Time ───────────────────────────────────────────────────

    def test_slash_19_routine_with_cron(self):
        """TC-SLASH-19: /routine <cron> → RegisterRoutineCmd."""
        self.skipTest("JLine terminal required. Verified by RoutineTimeCmdSpec.groovy")

    def test_slash_20_routine_no_args(self):
        """TC-SLASH-20: /routine (empty) → RegisterRoutineCmd."""
        self.skipTest("JLine terminal required. Verified by RoutineTimeCmdSpec.groovy")

    # ── Sub-Agent ──────────────────────────────────────────────────────

    def test_slash_21_spawn_with_goal(self):
        """TC-SLASH-21: /sub-agent spawn --goal → SpawnSubAgentCmd."""
        self.skipTest("JLine terminal required. Verified by SubAgentCmdParseSpec.groovy")

    def test_slash_22_spawn_with_model(self):
        """TC-SLASH-22: /sub-agent spawn --goal --model → SpawnSubAgentCmd."""
        self.skipTest("JLine terminal required. Verified by SubAgentCmdParseSpec.groovy")

    def test_slash_23_connect(self):
        """TC-SLASH-23: /sub-agent connect → ConnectSubAgentCmd."""
        self.skipTest("JLine terminal required. Verified by SubAgentCmdParseSpec.groovy")

    def test_slash_24_list(self):
        """TC-SLASH-24: /sub-agent list → ListSubAgentsCmd."""
        self.skipTest("JLine terminal required. Verified by SubAgentCmdParseSpec.groovy")

    def test_slash_25_cancel(self):
        """TC-SLASH-25: /sub-agent cancel <id> → CancelSubAgentCmd."""
        self.skipTest("JLine terminal required. Verified by SubAgentCmdParseSpec.groovy")

    def test_slash_26_results(self):
        """TC-SLASH-26: /sub-agent results <id> → GetSubAgentResultsCmd."""
        self.skipTest("JLine terminal required. Verified by SubAgentCmdParseSpec.groovy")

    def test_slash_27_send(self):
        """TC-SLASH-27: /sub-agent send <id> <msg> → SendToSubAgentCmd."""
        self.skipTest("JLine terminal required. Verified by SubAgentCmdParseSpec.groovy")

    def test_slash_28_prompt(self):
        """TC-SLASH-28: /sub-agent prompt <id> <prompt> → PromptSubAgentCmd."""
        self.skipTest("JLine terminal required. Verified by SubAgentCmdParseSpec.groovy")

    # ── Edge Cases ─────────────────────────────────────────────────────

    def test_slash_29_unknown(self):
        """TC-SLASH-29: /unknown → null (graceful rejection)."""
        self.skipTest("JLine terminal required. Verified by AgentCommandParseSpec.groovy")

    def test_slash_30_empty(self):
        """TC-SLASH-30: Empty line → null (ignored)."""
        self.skipTest("JLine terminal required. Verified by AgentCommandParseSpec.groovy")


if __name__ == "__main__":
    print("=" * 64)
    print("  E2E Case: TUI Slash Commands (case-tui-slash-commands.md)")
    print(f"  Project root: {PROJECT_ROOT}")
    print("=" * 64)
    unittest.main(verbosity=2)
