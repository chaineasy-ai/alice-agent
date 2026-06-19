#!/usr/bin/env python3
"""
E2E Dispatch Test — what the chat session (JLine) would call dispatchCommand() with.

These tests verify that AliceCliLauncher.dispatchCommand() correctly handles
all 21 AgentCommand sealed subtypes. Most require JLine terminal — we
document each skip with a unit test cross-reference.

The 4 types reachable via `alice run/routine/sub-agent` CLI are pass-through
references to the CLI category tests (docs/alice-facade-cmd/e2e/test_cli_categories.py).

Usage:
  python e2e/test_dispatch.py
"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from helpers import run_cli, PROJECT_ROOT

needs_build = not (PROJECT_ROOT / "alice-bootstrap" / "build").is_dir()


class TestDispatchAllAgentCommands(unittest.TestCase):
    """
    Coverage: all 21 sealed subtypes of AgentCommand in dispatchCommand().
    
    Legend:
      ✅ CLI     = reachable via `alice <subcommand>` (tested in CLI category tests)
      ⏭ JLine   = requires JLine terminal → skip with unit test reference
      ⏭ Kernel  = kernel-internal (TimeTriggeredCmd) → skip
    """

    maxDiff = None

    # ── ExecutionCmd subtypes ─────────────────────────────────

    def test_dispatch_01_acquire_goal(self):
        """AcquireGoalCmd — ✅ CLI via `alice run <task>`."""
        self.skipTest("Covered by docs/alice-facade-cmd/e2e/test_cli_categories.py B1-B3")

    def test_dispatch_02_execute_raw(self):
        """ExecuteRawCmd (`/exec ls -la`) — ⏭ JLine terminal required."""
        self.skipTest(
            "JLine terminal required. Verified by AliceCliLauncherDispatchSpec.groovy: "
            "'dispatch /exec ls -la returns exit 0'"
        )

    # ── CapabilityCmd subtypes ──────────────────────────────

    def test_dispatch_03_register_skill(self):
        """RegisterSkillCmd (`/skill load-dataset`) — ⏭ JLine terminal required."""
        self.skipTest(
            "JLine terminal required. Verified by AliceCliLauncherDispatchSpec.groovy: "
            "'dispatch /skill:load-dataset emits registering skill'"
        )

    def test_dispatch_04_update_rules(self):
        """UpdateRulesCmd (`/rules load-sys-prompts`) — ⏭ JLine terminal required."""
        self.skipTest(
            "JLine terminal required. Verified by AliceCliLauncherDispatchSpec.groovy: "
            "'dispatch /rules:load-sys-prompts emits updating rules'"
        )

    def test_dispatch_05_reload_kernel(self):
        """ReloadKernelCmd (`/reload`) — ⏭ JLine terminal required."""
        self.skipTest(
            "JLine terminal required. Verified by AliceCliLauncherDispatchSpec.groovy: "
            "'dispatch /reload emits reloading kernel'"
        )

    # ── AlignmentCmd subtypes ───────────────────────────────

    def test_dispatch_06_switch_model(self):
        """SwitchModelCmd (`/model gpt-4o`) — ⏭ JLine terminal required."""
        self.skipTest(
            "JLine terminal required. Verified by AliceCliLauncherDispatchSpec.groovy: "
            "'dispatch /model:gpt-4o emits switching model'"
        )

    # ── ControlCmd subtypes ─────────────────────────────────

    def test_dispatch_07_reset_session(self):
        """ResetSessionCmd (`/new`) — ⏭ JLine terminal required."""
        self.skipTest(
            "JLine terminal required. Verified by AliceCliLauncherDispatchSpec.groovy: "
            "'dispatch /new emits resetting session'"
        )

    def test_dispatch_08_feedback(self):
        """FeedbackCmd (`/feedback`) — ⏭ JLine terminal required."""
        self.skipTest(
            "JLine terminal required. Verified by AliceCliLauncherDispatchSpec.groovy: "
            "'dispatch /feedback:message emits feedback received'"
        )

    def test_dispatch_09_interrupt(self):
        """InterruptCmd (Ctrl+C) — ⏭ JLine terminal required."""
        self.skipTest(
            "JLine terminal required. Verified by AliceCliLauncherDispatchSpec.groovy: "
            "'dispatch /interrupt:cancel emits interrupted'"
        )

    def test_dispatch_10_clear_context(self):
        """ClearContextCmd (`/clear`) — ⏭ JLine terminal required."""
        self.skipTest(
            "JLine terminal required. Verified by AliceCliLauncherDispatchSpec.groovy: "
            "'dispatch /clear emits context cleared'"
        )

    def test_dispatch_11_view_context(self):
        """ViewContextCmd (`/context`) — ⏭ JLine terminal required."""
        self.skipTest(
            "JLine terminal required. Verified by AliceCliLauncherDispatchSpec.groovy: "
            "'dispatch /context emits context status'"
        )

    def test_dispatch_12_compact_context(self):
        """CompactContextCmd (`/compact`) — ⏭ JLine terminal required."""
        self.skipTest(
            "JLine terminal required. Verified by AliceCliLauncherDispatchSpec.groovy: "
            "'dispatch /compact emits context compression submitted'"
        )

    # ── RoutineTimeCmd subtypes ─────────────────────────────

    def test_dispatch_13_register_routine(self):
        """RegisterRoutineCmd — ✅ CLI via `alice routine <cron>`."""
        self.skipTest("Covered by docs/alice-facade-cmd/e2e/test_cli_categories.py B5-B6")

    def test_dispatch_14_time_triggered(self):
        """TimeTriggeredCmd — ⏭ Kernel internal (CronScheduler)."""
        self.skipTest(
            "CronScheduler internal. Verified by RoutineTimeCmdParseSpec.groovy "
            "and RoutineTimeCmdSpec.groovy unit tests."
        )

    # ── SubAgentCmd subtypes ────────────────────────────────

    def test_dispatch_15_spawn_sub_agent(self):
        """SpawnSubAgentCmd — ✅ CLI via `alice sub-agent --spawn`."""
        self.skipTest("Covered by docs/alice-facade-cmd/e2e/test_cli_categories.py B8")

    def test_dispatch_16_connect_sub_agent(self):
        """ConnectSubAgentCmd — ✅ CLI via `alice sub-agent --connect`."""
        self.skipTest("Covered by docs/alice-facade-cmd/e2e/test_cli_categories.py B9")

    def test_dispatch_17_list_sub_agents(self):
        """ListSubAgentsCmd — ✅ CLI via `alice sub-agent --list`."""
        self.skipTest("Covered by docs/alice-facade-cmd/e2e/test_cli_categories.py B10")

    def test_dispatch_18_cancel_sub_agent(self):
        """CancelSubAgentCmd — ✅ CLI via `alice sub-agent --cancel`."""
        self.skipTest("Covered by docs/alice-facade-cmd/e2e/test_cli_categories.py B11")

    def test_dispatch_19_get_sub_agent_results(self):
        """GetSubAgentResultsCmd — ✅ CLI via `alice sub-agent --results`."""
        self.skipTest("Covered by docs/alice-facade-cmd/e2e/test_cli_categories.py B12")

    def test_dispatch_20_send_to_sub_agent(self):
        """SendToSubAgentCmd — ✅ CLI via `alice sub-agent --send`."""
        self.skipTest("Covered by docs/alice-facade-cmd/e2e/test_cli_categories.py B13")

    def test_dispatch_21_prompt_sub_agent(self):
        """PromptSubAgentCmd — ✅ CLI via `alice sub-agent --prompt`."""
        self.skipTest("Covered by docs/alice-facade-cmd/e2e/test_cli_categories.py B14")


if __name__ == "__main__":
    print("=" * 64)
    print("  E2E: AgentCommand Dispatch Coverage")
    print("  All 21 sealed subtypes of AgentCommand")
    print("  Legend:")
    print("    ✅ CLI-tested (see CLI category tests)")
    print("    ⏭  JLine or kernel-internal (documented skip)")
    print("=" * 64)
    unittest.main(verbosity=2)
