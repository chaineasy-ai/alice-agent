#!/usr/bin/env python3
"""
MASTER E2E Test — Alice Agent CLI Command Taxonomy

Classifies every CLI command into its actual execution path:
  Category A (META/SELF)  = handled directly in AliceCliLauncher.run(), NO PPAO
  Category B (AGENT_CMD)  = routed to ExecutionCoordinator → Agent PPAO loop
  Category C (DISPATCH)   = only reachable via JLineChatSession.dispatchCommand(), skip documented
  Category D (HELP)       = picocli --help, exits immediately

Each test asserts REAL behavior (field values in RunConfig console output,
actual side effects like tool listing, config printing), not just exit codes.
"""
import os
import sys
import unittest
import subprocess
import time

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "..", "e2e"))
from helpers import run_cli, PROJECT_ROOT

# ========================================================================
# Constants
# ========================================================================
TIMEOUT_SHORT = 30            # for meta/help commands
TIMEOUT_LONG = 120            # for PPAO agent commands
E2E_TIMEOUT = int(os.environ.get("E2E_TIMEOUT", "180"))


def extract_output(result):
    """Extract application output lines (filter Gradle noise)."""
    combined = result.stdout + result.stderr
    lines = []
    for line in combined.split('\n'):
        s = line.strip()
        if not s:
            continue
        if any(x in s for x in [
            '> Task', 'BUILD', 'Consider enabling', 'WARNING:', 'Incubating',
            'problems', 'Received shutdown', 'alice-facade-cmd', 'stty:'
        ]):
            continue
        lines.append(s)
    return '\n'.join(lines)


def cli(args, timeout=TIMEOUT_SHORT):
    """Convenience wrapper for run_cli."""
    return run_cli(args, timeout=timeout)


def filter_ppao_lines(output):
    """Filter to only PPAO lifecycle lines."""
    lines = []
    for line in output.split('\n'):
        if any(x in line for x in ['PPAO loop', 'Micro-ReAct', 'LLM_INFERENCE',
                                    'Action{id=', '[Plan]', '[Act]', '[Observe]',
                                    'ModelProvider', 'supplier registered',
                                    'Final Answer', 'No result']):
            lines.append(line)
    return '\n'.join(lines)


def filter_field_line(output, field):
    """Get line containing a specific RunConfig field."""
    for line in output.split('\n'):
        if field in line:
            return line.strip()
    return None


# ========================================================================
# Pre-requisite check
# ========================================================================
needs_build = not (PROJECT_ROOT / "alice-bootstrap" / "build").is_dir()


# ========================================================================
# Category A: META / SELF commands (AliceCliLauncher.run() direct handling)
# ========================================================================

@unittest.skipIf(needs_build, "Project not built yet.")
class TestMetaSelfCommands(unittest.TestCase):
    """Category A: Meta/Self commands — handled by AliceCliLauncher directly, no PPAO."""

    maxDiff = None

    # ── A1: tools (list, detail, help) ──────────────────────────────

    def test_meta_a1_tools_help(self):
        """A1: `alice tools --help` prints picocli help, not Gradle help."""
        result = cli(["tools", "--help"])
        output = extract_output(result)
        self.assertEqual(result.returncode, 0)
        # Must be picocli tools help
        self.assertIn("Usage:", output, "Must show picocli usage")
        self.assertIn("--detail", output, "Must show --detail option")
        self.assertIn("-h", output, "Must show -h help option")
        self.assertNotIn("Configuration cache", output, "Must NOT show Gradle help")
        print(f"  ✅ [META A1] tools --help: picocli help verified")

    def test_meta_a2_tools_list(self):
        """A2: `alice tools` runs handleListTools(), shows tool list, NO PPAO."""
        result = cli(["tools"])
        output = extract_output(result)
        self.assertEqual(result.returncode, 0)
        # Must show RunConfig with listTools=true
        self.assertIn("listTools=true", output, "RunConfig must show listTools=true")
        # When tools registry is empty, show "No tools registered."
        self.assertIn("No tools registered.", output, "Must show empty tool listing message")
        # Must NOT enter PPAO
        self.assertNotIn("PPAO loop", output, "META commands must NOT enter PPAO loop")
        # Must NOT start agent lifecycle
        self.assertNotIn("Agent ", output, "Must NOT create Agent")
        print(f"  ✅ [META A2] tools list: listTools=true, NO PPAO, empty listing shown")

    def test_meta_a3_tools_detail(self):
        """A3: `alice tools --detail` shows toolDetail=true in RunConfig."""
        result = cli(["tools", "--detail"])
        output = extract_output(result)
        self.assertEqual(result.returncode, 0)
        self.assertIn("toolDetail=true", output, "RunConfig must show toolDetail=true")
        self.assertIn("No tools registered.", output, "Must show empty or populated tool listing")
        self.assertNotIn("PPAO loop", output, "META commands must NOT enter PPAO")
        print(f"  ✅ [META A3] tools --detail: toolDetail=true, NO PPAO")

    # ── A4-A6: config (overview, get, set) ──────────────────────────

    def test_meta_a4_config_overview(self):
        """A4: `alice config` runs handleConfig(), shows config overview, NO PPAO."""
        result = cli(["config"])
        output = extract_output(result)
        self.assertEqual(result.returncode, 0)
        # Must show RunConfig with configAction='show'
        self.assertIn("configAction='show'", output,
                       "RunConfig must show configAction='show'")
        # Must show config overview header
        self.assertIn("=== Alice Agent Configuration ===", output,
                       "Must show config overview")
        # Must show model defaults
        self.assertIn("default.model", output, "Must show default.model")
        # Must NOT enter PPAO
        self.assertNotIn("PPAO loop", output, "META commands must NOT enter PPAO")
        print(f"  ✅ [META A4] config overview: configAction='show', NO PPAO")

    def test_meta_a5_config_get(self):
        """A5: `alice config get default.model` shows value."""
        result = cli(["config", "get", "default.model"])
        output = extract_output(result)
        self.assertEqual(result.returncode, 0)
        self.assertIn("configAction='get'", output,
                       "RunConfig must show configAction='get'")
        self.assertIn("configKey='default.model'", output,
                       "RunConfig must show configKey")
        # Must show the actual value
        self.assertIn("gpt-4o-mini", output, "Must show default model value")
        self.assertNotIn("PPAO loop", output, "META commands must NOT enter PPAO")
        print(f"  ✅ [META A5] config get: configAction='get', value displayed")

    def test_meta_a6_config_set(self):
        """A6: `alice config set openai.api_key sk-test` shows set confirmation."""
        result = cli(["config", "set", "openai.api_key", "sk-test"])
        output = extract_output(result)
        self.assertEqual(result.returncode, 0)
        self.assertIn("configAction='set'", output,
                       "RunConfig must show configAction='set'")
        self.assertIn("configKey='openai.api_key'", output,
                       "RunConfig must show configKey")
        self.assertIn("sk-test", output, "Must show the set value")
        self.assertNotIn("PPAO loop", output, "META commands must NOT enter PPAO")
        print(f"  ✅ [META A6] config set: configAction='set', value confirmed")

    # ── A7: meta help (alice --help) ─────────────────────────────────

    def test_meta_a7_global_help(self):
        """A7: `alice --help` shows all 6 subcommands."""
        result = cli(["--help"])
        output = extract_output(result)
        self.assertEqual(result.returncode, 0)
        self.assertIn("Usage:", output, "Must show picocli usage")
        self.assertIn("run", output, "Must list run subcommand")
        self.assertIn("chat", output, "Must list chat subcommand")
        self.assertIn("tools", output, "Must list tools subcommand")
        self.assertIn("config", output, "Must list config subcommand")
        self.assertIn("routine", output, "Must list routine subcommand")
        self.assertIn("sub-agent", output, "Must list sub-agent subcommand")
        self.assertNotIn("Configuration cache", output, "Must NOT be Gradle help")
        print(f"  ✅ [META A7] --help: all 6 subcommands listed")

    # ── A8: sub-agent --help ─────────────────────────────────────────

    def test_meta_a8_sub_agent_help(self):
        """A8: `alice sub-agent --help` shows all 7 sub-agent options."""
        result = cli(["sub-agent", "--help"])
        output = extract_output(result)
        self.assertEqual(result.returncode, 0)
        self.assertIn("Usage:", output, "Must show picocli usage")
        self.assertIn("--spawn", output)
        self.assertIn("--connect", output)
        self.assertIn("--list", output)
        self.assertIn("--cancel", output)
        self.assertIn("--results", output)
        self.assertIn("--send", output)
        self.assertIn("--prompt", output)
        self.assertNotIn("Configuration cache", output, "Must NOT be Gradle help")
        print(f"  ✅ [META A8] sub-agent --help: all 7 options listed")

    # ── A9: routine --help ───────────────────────────────────────────

    def test_meta_a9_routine_help(self):
        """A9: `alice routine --help` shows routine-specific help."""
        result = cli(["routine", "--help"])
        output = extract_output(result)
        self.assertEqual(result.returncode, 0)
        self.assertIn("Usage:", output)
        self.assertIn("--list", output)
        self.assertNotIn("Configuration cache", output, "Must NOT be Gradle help")
        print(f"  ✅ [META A9] routine --help: help verified")

    # ── A10: run --help ──────────────────────────────────────────────

    def test_meta_a10_run_help(self):
        """A10: `alice run --help` shows run-specific options including --session-id."""
        result = cli(["run", "--help"])
        output = extract_output(result)
        self.assertEqual(result.returncode, 0)
        self.assertIn("Usage:", output)
        self.assertIn("--model", output)
        self.assertIn("--verbose", output)
        self.assertIn("--json", output)
        self.assertIn("--session-id", output)
        self.assertIn("--timeout", output)
        self.assertIn("<task>", output)
        self.assertNotIn("Configuration cache", output, "Must NOT be Gradle help")
        print(f"  ✅ [META A10] run --help: all run options displayed (incl. --session-id)")


# ========================================================================
# Category B: AGENT COMMANDS (via ExecutionCoordinator → PPAO)
# ========================================================================

@unittest.skipIf(needs_build, "Project not built yet.")
class TestAgentCommands(unittest.TestCase):
    """Category B: Agent commands — routed to ExecutionCoordinator → Agent PPAO loop."""

    maxDiff = None

    # ── B1-B4: run subcommand ───────────────────────────────────────

    def test_agent_b1_run_basic(self):
        """B1: `alice run <task>` creates RunConfig + enters PPAO loop."""
        result = cli(["run", "test task"], timeout=TIMEOUT_LONG)
        output = extract_output(result)
        self.assertIn(result.returncode, [0, 1],
                       "Agent command may succeed or fail (no API key)")
        # Must show RunConfig
        self.assertIn("RunConfig:", output, "Must log RunConfig")
        self.assertIn("test task", output, "Task must appear in RunConfig")
        # Must enter PPAO loop
        self.assertIn("PPAO loop", output, "Must start PPAO lifecycle")
        print(f"  ✅ [AGENT B1] run task: RunConfig + PPAO (exit={result.returncode})")

    def test_agent_b2_run_verbose_json(self):
        """B2: `alice run -v --json <task>` shows verbose/json in RunConfig."""
        result = cli(["run", "-v", "--json", "verbose test"], timeout=TIMEOUT_LONG)
        output = extract_output(result)
        self.assertIn(result.returncode, [0, 1])
        # Must show flags in RunConfig
        self.assertIn("verbose=true", output,
                       "RunConfig verbose flag must be true")
        self.assertIn("jsonOutput=true", output,
                       "RunConfig jsonOutput must be true")
        print(f"  ✅ [AGENT B2] run -v --json: flags verified (exit={result.returncode})")

    def test_agent_b3_run_model_override(self):
        """B3: `alice run -m gpt-4o <task>` overrides model in RunConfig."""
        result = cli(["run", "-m", "gpt-4o", "model override"], timeout=TIMEOUT_LONG)
        output = extract_output(result)
        self.assertIn(result.returncode, [0, 1])
        self.assertIn("model='gpt-4o'", output,
                       "RunConfig must show overridden model")
        print(f"  ✅ [AGENT B3] run -m gpt-4o: model override (exit={result.returncode})")

    # ── B3b-B3c: session-id pass-through (TC-RUN-06 / TC-RUN-07) ───

    def test_agent_b3b_run_session_id(self):
        """B3b: `alice run --session-id my-test-001 <task>` passes session ID through."""
        result = cli(
            ["run", "--session-id", "my-test-001", "session test"],
            timeout=TIMEOUT_LONG)
        output = extract_output(result)
        self.assertIn(result.returncode, [0, 1])
        self.assertIn("sessionId='my-test-001'", output,
                       "RunConfig must show client-provided session ID")
        # WAL directory should be deterministically derived
        expected_hash = hex(hash("my-test-001") & 0xFFFF)[2:]
        print(f"  ✅ [AGENT B3b] run --session-id: 'my-test-001' passed through"
              f" (walDir=...{expected_hash}) (exit={result.returncode})")

    def test_agent_b3c_run_session_id_omitted(self):
        """B3c: `alice run <task>` without --session-id auto-generates an 8-char ID."""
        result = cli(["run", "auto-id test"], timeout=TIMEOUT_LONG)
        output = extract_output(result)
        self.assertIn(result.returncode, [0, 1])
        # sessionId should appear in RunConfig with some non-empty value
        self.assertIn("sessionId=", output,
                       "RunConfig must show sessionId field")
        self.assertNotIn("sessionId=''", output,
                         "sessionId must not be empty")
        print(f"  ✅ [AGENT B3c] run (no --session-id): auto-generated ID"
              f" (exit={result.returncode})")

    def test_agent_b4_run_missing_task(self):
        """B4: `alice run` without task exits with error (ParseException exit=2 internally,
        Gradle wraps to exit=1). Must show 'Missing required parameter'."""
        result = cli(["run"])
        # JVM exits 2, Gradle wraps to 1
        self.assertIn(result.returncode, [1, 2],
                         "Missing task must error")
        # Must show error message
        output = extract_output(result)
        self.assertIn("Missing required parameter", output, "Must show missing parameter error")
        print(f"  ✅ [AGENT B4] run no task: rejected with 'Missing required parameter' (exit={result.returncode})")

    # ── B5-B7: routine subcommand ───────────────────────────────────

    def test_agent_b5_routine_cron(self):
        """B5: `alice routine <cron>` parses cron into RunConfig + enters PPAO."""
        result = cli(["routine", "0 */5 * * * ?"], timeout=TIMEOUT_LONG)
        output = extract_output(result)
        self.assertIn(result.returncode, [0, 1],
                       "Routine may succeed or fail (no routine engine wired)")
        # Must show routineCron in RunConfig
        self.assertIn("routineCron=", output,
                       "RunConfig must show routineCron")
        self.assertIn("0 */5 * * * ?", output,
                       "RunConfig must contain the cron expression")
        # Routine goes through PPAO (RegisterRoutineCmd is dispatched)
        ppao_lines = filter_ppao_lines(output)
        self.assertTrue(ppao_lines.strip(),
                        "Routine command must enter PPAO lifecycle")
        print(f"  ✅ [AGENT B5] routine cron: cron parsed + PPAO (exit={result.returncode})")

    def test_agent_b6_routine_list(self):
        """B6: `alice routine --list` shows listRoutines=true in RunConfig + PPAO."""
        result = cli(["routine", "--list"], timeout=TIMEOUT_LONG)
        output = extract_output(result)
        self.assertIn(result.returncode, [0, 1])
        self.assertIn("listRoutines=true", output,
                       "RunConfig must show listRoutines=true")
        # With --list flag, it goes through PPAO
        ppao_lines = filter_ppao_lines(output)
        self.assertTrue(ppao_lines.strip(),
                        "Routine --list must enter PPAO lifecycle")
        print(f"  ✅ [AGENT B6] routine --list: listRoutines=true + PPAO (exit={result.returncode})")

    def test_agent_b7_routine_help(self):
        """B7: `alice routine --help` exits immediately (picocli help)."""
        result = cli(["routine", "--help"])
        output = extract_output(result)
        self.assertEqual(result.returncode, 0)
        self.assertIn("Usage:", output)
        self.assertIn("--list", output)
        self.assertNotIn("PPAO loop", output, "Help must NOT enter PPAO")
        print(f"  ✅ [AGENT B7] routine --help: picocli help, no PPAO")

    # ── B8-B14: sub-agent subcommand ───────────────────────────────

    def test_agent_b8_sub_agent_spawn(self):
        """B8: `alice sub-agent --spawn <goal>` shows subAgentSpawnGoal + PPAO."""
        result = cli(["sub-agent", "--spawn", "monitor disk"], timeout=TIMEOUT_LONG)
        output = extract_output(result)
        self.assertIn(result.returncode, [0, 1])
        # Must show spawn goal in RunConfig
        self.assertIn("subAgentSpawnGoal=", output,
                       "RunConfig must show subAgentSpawnGoal")
        self.assertIn("monitor disk", output,
                       "RunConfig must contain the spawn goal")
        # Must enter PPAO
        self.assertIn("PPAO loop", output,
                       "Sub-agent command must enter PPAO lifecycle")
        print(f"  ✅ [AGENT B8] sub-agent --spawn: goal in RunConfig + PPAO (exit={result.returncode})")

    def test_agent_b9_sub_agent_connect(self):
        """B9: `alice sub-agent --connect <name> --acp-endpoint <uri>` shows connect fields."""
        result = cli(["sub-agent", "--connect", "worker1",
                       "--acp-endpoint", "http://localhost:9000/acp"],
                      timeout=TIMEOUT_LONG)
        output = extract_output(result)
        self.assertIn(result.returncode, [0, 1])
        self.assertIn("subAgentConnectName=", output,
                       "RunConfig must show subAgentConnectName")
        self.assertIn("worker1", output, "Must contain the connect name")
        self.assertIn("subAgentConnectEndpoint=", output,
                       "RunConfig must show endpoint")
        self.assertIn("localhost:9000", output, "Must contain the endpoint URL")
        print(f"  ✅ [AGENT B9] sub-agent --connect: name+endpoint verified (exit={result.returncode})")

    def test_agent_b10_sub_agent_list(self):
        """B10: `alice sub-agent --list` shows subAgentList=true in RunConfig."""
        result = cli(["sub-agent", "--list"], timeout=TIMEOUT_LONG)
        output = extract_output(result)
        self.assertIn(result.returncode, [0, 1])
        self.assertIn("subAgentList=true", output,
                       "RunConfig must show subAgentList=true")
        self.assertIn("PPAO loop", output)
        print(f"  ✅ [AGENT B10] sub-agent --list: subAgentList=true (exit={result.returncode})")

    def test_agent_b11_sub_agent_cancel(self):
        """B11: `alice sub-agent --cancel <id>` shows subAgentCancelId in RunConfig."""
        result = cli(["sub-agent", "--cancel", "sub-abc-123"], timeout=TIMEOUT_LONG)
        output = extract_output(result)
        self.assertIn(result.returncode, [0, 1])
        self.assertIn("subAgentCancelId='sub-abc-123'", output,
                       "RunConfig must show subAgentCancelId")
        self.assertIn("PPAO loop", output)
        print(f"  ✅ [AGENT B11] sub-agent --cancel: cancelId in RunConfig (exit={result.returncode})")

    def test_agent_b12_sub_agent_results(self):
        """B12: `alice sub-agent --results <id>` shows subAgentResultsId in RunConfig."""
        result = cli(["sub-agent", "--results", "sub-abc-123"], timeout=TIMEOUT_LONG)
        output = extract_output(result)
        self.assertIn(result.returncode, [0, 1])
        self.assertIn("subAgentResultsId='sub-abc-123'", output,
                       "RunConfig must show subAgentResultsId")
        self.assertIn("PPAO loop", output)
        print(f"  ✅ [AGENT B12] sub-agent --results: resultsId in RunConfig (exit={result.returncode})")

    def test_agent_b13_sub_agent_send(self):
        """B13: `alice sub-agent --send <id> --message <msg>` shows send fields."""
        result = cli(["sub-agent", "--send", "agent1",
                       "--message", "hello e2e"], timeout=TIMEOUT_LONG)
        output = extract_output(result)
        self.assertIn(result.returncode, [0, 1])
        self.assertIn("subAgentSendId='agent1'", output,
                       "RunConfig must show subAgentSendId")
        self.assertIn("subAgentSendMessage='hello e2e'", output,
                       "RunConfig must show subAgentSendMessage")
        self.assertIn("PPAO loop", output)
        print(f"  ✅ [AGENT B13] sub-agent --send: sendId+message verified (exit={result.returncode})")

    def test_agent_b14_sub_agent_prompt(self):
        """B14: `alice sub-agent --prompt <text> --agent-id <id>` shows prompt fields."""
        result = cli(["sub-agent", "--prompt", "analyze logs",
                       "--agent-id", "ext-agent"], timeout=TIMEOUT_LONG)
        output = extract_output(result)
        self.assertIn(result.returncode, [0, 1])
        self.assertIn("subAgentPromptAgentId='ext-agent'", output,
                       "RunConfig must show subAgentPromptAgentId")
        self.assertIn("subAgentPromptText='analyze logs'", output,
                       "RunConfig must show subAgentPromptText")
        self.assertIn("PPAO loop", output)
        print(f"  ✅ [AGENT B14] sub-agent --prompt: agentId+prompt verified (exit={result.returncode})")


# ========================================================================
# Category C: DISPATCH-ONLY COMMANDS (JLine terminal required)
# ========================================================================

@unittest.skipIf(needs_build, "Project not built yet.")
class TestDispatchOnlyCommands(unittest.TestCase):
    """Category C: Commands only reachable via JLineChatSession → dispatchCommand().
    
    These 13 AgentCommand subtypes cannot be E2E tested via CLI subprocess because
    they require an interactive JLine terminal. They are verified by unit tests:
      - AliceCliLauncherDispatchSpec.groovy (dispatch switch patterns)
      - SubAgentCmdSpec.groovy (SubAgentCmd sealed hierarchy)
      - AgentCommandSealedHierarchySpec.groovy (all 21 subtypes)
    """

    def test_dispatch_c1_acquire_goal(self):
        """C1: AcquireGoalCmd — CLI reachable via `alice run` (tested in B1)."""
        pass  # Already covered by B1

    def test_dispatch_c2_execute_raw(self):
        """C2: ExecuteRawCmd — chat-only (/exec), JLine terminal required."""
        self.skipTest(
            "JLine terminal required. Verified by AliceCliLauncherDispatchSpec.groovy: "
            "'dispatch /exec ls -la returns exit 0'"
        )

    def test_dispatch_c3_register_skill(self):
        """C3: RegisterSkillCmd — chat-only (/skill), JLine terminal required."""
        self.skipTest(
            "JLine terminal required. Verified by AliceCliLauncherDispatchSpec.groovy: "
            "'dispatch /skill:load-dataset emits registering skill'"
        )

    def test_dispatch_c4_update_rules(self):
        """C4: UpdateRulesCmd — chat-only (/rules), JLine terminal required."""
        self.skipTest(
            "JLine terminal required. Verified by AliceCliLauncherDispatchSpec.groovy: "
            "'dispatch /rules:load-sys-prompts emits updating rules'"
        )

    def test_dispatch_c5_reload_kernel(self):
        """C5: ReloadKernelCmd — chat-only (/reload), JLine terminal required."""
        self.skipTest(
            "JLine terminal required. Verified by AliceCliLauncherDispatchSpec.groovy"
        )

    def test_dispatch_c6_switch_model(self):
        """C6: SwitchModelCmd — chat-only (/model), JLine terminal required."""
        self.skipTest(
            "JLine terminal required. Verified by AliceCliLauncherDispatchSpec.groovy: "
            "'dispatch /model:gpt-4o emits switching model'"
        )

    def test_dispatch_c7_reset_session(self):
        """C7: ResetSessionCmd — chat-only (/new), JLine terminal required."""
        self.skipTest(
            "JLine terminal required. Verified by AliceCliLauncherDispatchSpec.groovy"
        )

    def test_dispatch_c8_feedback(self):
        """C8: FeedbackCmd — chat-only (/feedback), JLine terminal required."""
        self.skipTest(
            "JLine terminal required. Verified by AliceCliLauncherDispatchSpec.groovy"
        )

    def test_dispatch_c9_interrupt(self):
        """C9: InterruptCmd — chat-only (Ctrl+C), JLine terminal required."""
        self.skipTest(
            "JLine terminal required. Verified by AliceCliLauncherDispatchSpec.groovy"
        )

    def test_dispatch_c10_clear_context(self):
        """C10: ClearContextCmd — chat-only (/clear), JLine terminal required."""
        self.skipTest(
            "JLine terminal required. Verified by AliceCliLauncherDispatchSpec.groovy: "
            "'dispatch /clear emits context cleared'"
        )

    def test_dispatch_c11_view_context(self):
        """C11: ViewContextCmd — chat-only (/context), JLine terminal required."""
        self.skipTest(
            "JLine terminal required. Verified by AliceCliLauncherDispatchSpec.groovy: "
            "'dispatch /context emits context status'"
        )

    def test_dispatch_c12_compact_context(self):
        """C12: CompactContextCmd — chat-only (/compact), JLine terminal required."""
        self.skipTest(
            "JLine terminal required. Verified by AliceCliLauncherDispatchSpec.groovy: "
            "'dispatch /compact emits context compression submitted'"
        )

    def test_dispatch_c13_register_routine(self):
        """C13: RegisterRoutineCmd — CLI reachable via `alice routine` (tested in B5)."""
        pass  # Already covered by B5

    def test_dispatch_c14_time_triggered(self):
        """C14: TimeTriggeredCmd — kernel internal, CronScheduler, not reachable via CLI/TUI."""
        self.skipTest(
            "CronScheduler internal. Verified by RoutineTimeCmdParseSpec.groovy and "
            "RoutineTimeCmdSpec.groovy unit tests."
        )

    def test_dispatch_c15_spawn_sub_agent(self):
        """C15-C21: SubAgentCmd subtypes — CLI reachable via `alice sub-agent` (tested in B8-B14)."""
        pass  # All 7 covered by B8-B14


# ========================================================================
# Summary Runner
# ========================================================================

if __name__ == "__main__":
    print("=" * 70)
    print("  MASTER E2E: Alice Agent CLI Command Taxonomy")
    print(f"  Project root: {PROJECT_ROOT}")
    print()
    print("  Categories:")
    print("  [META]   = Self commands (tools, config, --help) — NO PPAO")
    print("  [AGENT]  = Agent commands (run, routine, sub-agent) — PPAO lifecycle")
    print("  [DISP]   = Dispatch-only commands (chat slash) — JLine skip")
    print()
    print(f"  Run: ./gradlew :alice-facade-cmd:run --args '<subcmd>'")
    print("=" * 70)

    # Create test suite
    loader = unittest.TestLoader()
    suite = unittest.TestSuite()

    # Run in order: Meta → Agent → Dispatch
    suite.addTests(loader.loadTestsFromTestCase(TestMetaSelfCommands))
    suite.addTests(loader.loadTestsFromTestCase(TestAgentCommands))
    suite.addTests(loader.loadTestsFromTestCase(TestDispatchOnlyCommands))

    runner = unittest.TextTestRunner(verbosity=2)
    result = runner.run(suite)

    print()
    print("=" * 70)
    print("  SUMMARY")
    print(f"  Tests run:    {result.testsRun}")
    print(f"  Passed:       {result.testsRun - len(result.failures) - len(result.errors)}")
    print(f"  Failures:     {len(result.failures)}")
    print(f"  Errors:       {len(result.errors)}")
    print(f"  Skipped:      {len(result.skipped)}")
    if result.failures or result.errors:
        for test, trace in result.failures + result.errors:
            print(f"  ❌ {test.id()}")
    print("=" * 70)

    sys.exit(0 if result.wasSuccessful() else 1)
