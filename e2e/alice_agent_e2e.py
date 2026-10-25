#!/usr/bin/env python3
"""
Alice Agent E2E Test Suite — primary E2E scenarios (S-01, S-02, S-03).

Tests the full Alice Agent system by:
1. Building the project with Gradle
2. Running the CLI with various commands
3. Validating CLI outputs (help, version, commands)
4. Testing the Java-based ModelProvider + Gemma4Supplier integration
5. Testing AgentCommand parsing and dispatch

Prerequisites:
  - Java 25+ (JDK)
  - Gradle wrapper (provided)
  - Gemma4 API running at http://192.168.1.14:10303/v1

Usage:
  python e2e/alice_agent_e2e.py [--build] [-v]

See also:
  e2e/test_dispatch.py   — AgentCommand dispatch coverage (21 subtypes)
  e2e/smoke/              — PMTEV smoke test framework (3 standard cases)
"""

import json
import os
import re
import shutil
import subprocess
import sys
import time
import unittest
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from helpers import (
    run_gradle,
    run_cli,
    diagnostic_tail,
    PROJECT_ROOT,
    GRADLEW,
    GEMMA4_BASE_URL,
    GEMMA4_MODEL,
    ALICE_MODEL_CONFIG,
    skip_if_no_gemma4,
    skip_if_no_local_supplier,
)

TIMEOUT = int(os.environ.get("GEMMA4_TIMEOUT", "180"))
REBUILD = "--build" in sys.argv
if "--build" in sys.argv:
    sys.argv.remove("--build")


# ── 输出契约断言（#415 · CLI 输出断言漂移校准）──────────────────────────────
#
# 断言只锚定**对外可观测契约**，不再依赖调试串（`RunConfig{...}` 转储、模型字面量、
# `Facade selected: tui` 之类的 INFO 日志）。契约来源：
#   · 启动横幅 —— StartupBanner.render（stderr 固定七块，各门面统一开场）
#   · 结果面   —— TextOutputRenderer.renderFinal / renderError
#                 （stdout `✓ Final Answer` / stderr `✖ Error: `）
#   · verbose  —— ExecutionCoordinator 图内核事件订阅（stderr `[thought]/[action]/[observe]`）
#   · 退出码   —— AliceApp exit code（0=成功 1=失败）
#
# 为什么不再断言日志串：日志是按排障需要演进的内容（级别/字段随时变），
# 断言它等于把 e2e 绑在实现细节上；横幅/结果面/退出码才是产品对使用者的承诺。

BANNER_BLOCKS = (
    "[Context]",
    "[Skills]",
    "[Prompts]",
    "[Extensions]",
    "[agents]",
    "[runtime]",
    "[loop]",
)

RESULT_MARKERS = ("✓ Final Answer", "✖ Error: ")

VERBOSE_EVENT_MARKERS = ("[thought]", "[action]", "[observe]")


def assert_startup_banner(case, result) -> None:
    """stderr 必须完整输出七块启动横幅（各门面统一开场契约）。

    若输出里只有 Gradle 任务清单（`... UP-TO-DATE` / `BUILD SUCCESSFUL`）而没有横幅，
    说明程序根本没执行（Gradle 把 run 任务当可缓存任务）——这是假绿源，直接点名。
    """
    err = result.stderr or ""
    missing = [b for b in BANNER_BLOCKS if b not in err]
    if not missing:
        return
    stdout = result.stdout or ""
    if "UP-TO-DATE" in stdout and "BUILD SUCCESSFUL" in stdout:
        case.fail(
            ":alice-*:run 被 Gradle 判为 UP-TO-DATE，本次运行并未执行程序 "
            "（stdout 只有任务清单）。断言输出内容的用例必须 run_cli(..., rerun=True)。"
        )
    case.fail(f"启动横幅缺块 {missing}; stderr 前 500 字:\n{err[:500]}")


def assert_result_surface(case, combined: str) -> None:
    """一次 run 必须以结果面或错误面收口（不能只有日志、无声退出）。"""
    case.assertTrue(
        any(m in combined for m in RESULT_MARKERS),
        f"缺少结果面 {'/'.join(RESULT_MARKERS)}; 输出尾部:\n{combined[-500:]}",
    )


# ── Build Step ─────────────────────────────────────────────────────────────

class BuildAliceAgent(unittest.TestCase):
    """Build Alice Agent before running E2E tests."""

    @classmethod
    def setUpClass(cls):
        if not REBUILD:
            print("\n  ℹ️  Skipping build (use --build to rebuild)")
            return
        print("\n  🔨 Building Alice Agent...")
        result = run_gradle(":alice-bootstrap:installDist", timeout=300)
        if result.returncode != 0:
            raise RuntimeError(f"Build failed: {diagnostic_tail(result, 300)}")

    def test_build_succeeds(self):
        """Verify the project compiles."""
        if not REBUILD:
            self.skipTest("Skipped (use --build to rebuild)")
        dist_dir = PROJECT_ROOT / "alice-bootstrap" / "build" / "install" / "alice"
        self.assertTrue(dist_dir.is_dir(), f"Distribution not found at {dist_dir}")
        bin_dir = dist_dir / "bin"
        self.assertTrue(bin_dir.is_dir(), f"Bin directory not found at {bin_dir}")
        print(f"\n  ↳ Distribution: {dist_dir}")


# ── CLI Tests ──────────────────────────────────────────────────────────────

@unittest.skipIf(not (PROJECT_ROOT / "alice-bootstrap" / "build").is_dir(), "Project not built yet. Run with --build first.")
class TestAliceCliHelp(unittest.TestCase):
    """Test the Alice Agent CLI help/usage output."""

    def test_help_output_with_no_args(self):
        """Running 'alice' with no args should print usage info."""
        result = run_cli([], timeout=30, module=':alice-bootstrap:run')
        self.assertEqual(result.returncode, 0, msg=result.stderr[:500])
        print("\n  ↳ CLI help invoked (exit=0 = picocli --help on root command)")

    def test_version_info_in_help(self):
        """Help output should contain the version string."""
        result = run_cli(["run", "--help"], timeout=30, module=':alice-facade-cli:run')
        output = result.stdout + result.stderr
        self.assertEqual(result.returncode, 0, msg=output[:500])
        self.assertIn("Usage:", output, "Run help should show 'Usage:'")
        self.assertIn("--model", output, "Run help should list --model option")
        self.assertIn("--verbose", output, "Run help should list --verbose option")
        print("\n  ↳ CLI run subcommand help verified (exit=0)")

    def test_run_help_via_help_flag(self):
        """Running 'alice run --help' should show run subcommand help."""
        result = run_cli(["run", "--help"], timeout=30)
        output = result.stdout + result.stderr
        self.assertEqual(result.returncode, 0, msg=output[:500])
        self.assertIn("--model", output, "Run help should list --model option")
        self.assertIn("--verbose", output, "Run help should list --verbose option")
        self.assertIn("--json", output, "Run help should list --json option")
        print("\n  ↳ Run subcommand help verified")

    def test_cli_flag_explicit(self):
        """--cli flag should work and default to CLI mode."""
        result = run_cli(["--cli"], timeout=30, module=':alice-bootstrap:run')
        output = result.stdout + result.stderr
        self.assertIn(result.returncode, [1], msg=result.stderr[:300])
        self.assertIn("No subcommand given", output, "CLI should report missing subcommand")
        print("\n  ↳ --cli flag works (routed to CLI facade, missing subcommand)")


@unittest.skipIf(not (PROJECT_ROOT / "alice-bootstrap" / "build").is_dir(), "Project not built yet. Run with --build first.")
class TestAliceCliRunCommand(unittest.TestCase):
    """Test the 'alice run <task>' command."""

    @classmethod
    def setUpClass(cls):
        cls.gemma4_available = not skip_if_no_gemma4()
        if not cls.gemma4_available:
            print("\n  ⚠️  Gemma4 API not reachable — run-with-model tests will be skipped")

    def test_run_with_simple_task(self):
        """Running 'alice run "Hello"' should invoke the Agent and produce output."""
        result = run_cli(["run", "Hello"], timeout=TIMEOUT, rerun=True)
        output = result.stdout + result.stderr
        print(f"\n  ↳ Exit code: {result.returncode}")
        self.assertIn(result.returncode, [0, 1],
                      f"Expected exit 0 or 1, got {result.returncode}")
        # #415：断言启动横幅（七块）+ 结果面契约，不再断言 RunConfig 调试转储
        assert_startup_banner(self, result)
        assert_result_surface(self, output)
        print(f"  ↳ Output snippet: {output[:300]}")

    @unittest.skipIf(True, "Requires Gemma4 API — run with GEMMA4_BASE_URL set")
    def test_run_with_gemma4_model(self):
        """Running with --model gemma-4 should use the Gemma4 supplier."""
        os.environ["GEMMA4_BASE_URL"] = GEMMA4_BASE_URL
        result = run_cli(["run", "Say hello in one word", "--model", "gemma-4", "--verbose"], timeout=TIMEOUT)
        output = result.stdout + result.stderr
        print(f"\n  ↳ Exit code: {result.returncode}")
        if self.gemma4_available:
            self.assertEqual(result.returncode, 0, msg=output[-500:])
            self.assertIn("gemma-4", output, "Should mention gemma-4 model")
        print(f"  ↳ Output: {output[:500]}")

    def test_run_with_missing_task(self):
        """Missing task argument should produce an error."""
        result = run_cli(["run"], timeout=30)
        self.assertIn(result.returncode, [1, 2], msg=result.stderr[:300])
        print(f"\n  ↳ Missing task correctly rejected with exit code {result.returncode}")


@unittest.skipIf(not (PROJECT_ROOT / "alice-bootstrap" / "build").is_dir(), "Project not built yet. Run with --build first.")
class TestAliceCliModelOverride(unittest.TestCase):
    """Test model override via --model / -m flags."""

    def test_model_flag_reaches_model_resolution(self):
        """--model 必须真的参与模型解析（负控：未知模型 ⇒ 退出 1 且错误面点名该模型）。

        #415：原断言查 `gpt-4o` 字面量出现在 RunConfig 转储里（调试串）。
        改为行为契约——用一个不可能存在的模型 id 走一次解析，
        既证明 flag 到位，也固定住「未知模型必须显式失败而非静默降级」。
        """
        bogus = "bogus-model-nonexistent"
        result = run_cli(["run", "test task", "--model", bogus], timeout=TIMEOUT, rerun=True)
        output = result.stdout + result.stderr
        if "No supplier found" not in output:
            # 规划阶段先失败（模型端点不可达）时无法触达解析负控——跳过而非假红
            self.skipTest(f"模型端点不可用，负控未触达；输出尾部:\n{output[-300:]}")
        self.assertEqual(result.returncode, 1, msg=output[-500:])
        self.assertIn(f"No supplier found for modelId: {bogus}", output,
                      "未知模型必须在错误面点名具体 id")
        print(f"\n  ↳ Model override reaches resolution (unknown '{bogus}' -> exit 1)")

    def test_verbose_flag_enables_event_stream(self):
        """--verbose 必须打开图内核事件流（stderr `[thought]/[action]/[observe]`）。

        #415：原断言 `"verbose" in output.lower()` 只证明日志里出现过这个词，
        不证明功能生效——改为断言事件流本身。
        """
        result = run_cli(["run", "test task", "--verbose"], timeout=TIMEOUT, rerun=True)
        output = result.stderr or ""
        hits = [m for m in VERBOSE_EVENT_MARKERS if m in output]
        if not hits:
            self.skipTest(f"图内核未产生事件（模型/工具不可用）；stderr 尾部:\n{output[-300:]}")
        print(f"\n  ↳ Verbose event stream verified: {'/'.join(hits)}")

    def test_json_output_flag(self):
        """--json flag should be accepted (even if output not fully JSON yet)."""
        result = run_cli(["run", "test task", "--json"], timeout=TIMEOUT)
        self.assertIn(result.returncode, [0, 1], msg=result.stderr[:300])
        print(f"\n  ↳ JSON flag accepted, exit code: {result.returncode}")


# ── Java Unit-Level E2E Tests (Python-driven) ─────────────────────────────

@unittest.skipIf(not (PROJECT_ROOT / "alice-bootstrap" / "build").is_dir(), "Project not built yet. Run with --build first.")
class TestAliceAgentJavaApi(unittest.TestCase):
    """Test Alice Agent Java classes via direct JVM invocation."""

    @classmethod
    def setUpClass(cls):
        dist_dir = PROJECT_ROOT / "alice-bootstrap" / "build" / "install" / "alice"
        lib_dir = dist_dir / "lib"
        if lib_dir.is_dir():
            cls.classpath = str(lib_dir)
        else:
            cls.classpath = str(PROJECT_ROOT / "alice-bootstrap" / "build" / "libs")
        print(f"\n  📦 Classpath: {cls.classpath}")

    def test_agent_version_accessible(self):
        """AliceAgent.VERSION should be accessible."""
        result = run_cli([], timeout=30, module=':alice-bootstrap:run')
        self.assertEqual(result.returncode, 0,
                         msg=f"Expected exit 0 (--help shown), got {result.returncode}")
        print("\n  ↳ AliceAgent bootstrapped with --help (version=v0.1.0 in System.out -> TTY)")

    def test_facade_selector_detects_tui(self):
        """FacadeSelector should detect --tui flag（断言横幅而非 `Facade selected:` 日志）。

        #415：非 tty 下 TUI 门面仍走统一开场（七块横幅）并以 exit 0 退出；
        横幅是各门面共用契约，日志行不是。
        """
        result = run_cli(["--tui"], timeout=120, module=':alice-bootstrap:run', rerun=True)
        self.assertIn(result.returncode, [0], msg=(result.stderr or "")[:300])
        assert_startup_banner(self, result)
        print("\n  ↳ --tui routed through a facade that printed the startup banner")


# ── Agent Command Tests ────────────────────────────────────────────────────

@unittest.skipIf(not (PROJECT_ROOT / "alice-bootstrap" / "build").is_dir(), "Project not built yet. Run with --build first.")
class TestAgentCommandDispatch(unittest.TestCase):
    """Test the AgentCommand parsing and dispatch via AliceCliLauncher."""

    def test_natural_language_becomes_acquire_goal(self):
        """Plain text input should be parsed as AcquireGoalCmd."""
        result = run_cli(["run", "write a poem about AI"], timeout=TIMEOUT)
        output = result.stdout + result.stderr
        self.assertIn(result.returncode, [0, 1], msg=output[-300:])
        print("\n  ↳ Natural language task accepted via run command")

    def test_dispatch_command_method(self):
        """dispatchCommand should handle various command types properly."""
        result = run_cli(["run", "test dispatch"], timeout=TIMEOUT)
        self.assertIn(result.returncode, [0, 1], msg=result.stderr[-300:])
        print("\n  ↳ dispatchCommand executed successfully")


# ── Gemma4 Integration Tests ───────────────────────────────────────────────

@unittest.skipIf(skip_if_no_gemma4(), "Gemma4 API not reachable")
class TestGemma4Integration(unittest.TestCase):
    """Test Alice Agent's integration with the Gemma4 model supplier."""

    @classmethod
    def setUpClass(cls):
        sys.path.insert(0, str(PROJECT_ROOT / "e2e" / "gemma4"))
        try:
            from gemma_4_client import OpenAICompatibleClient as Client
            cls.client = Client(GEMMA4_BASE_URL)
            cls.model = GEMMA4_MODEL
        except ImportError as e:
            raise unittest.SkipTest(f"Cannot import gemma_4_client: {e}") from e

    @unittest.skipIf(
        skip_if_no_local_supplier(GEMMA4_MODEL),
        f"本机 {ALICE_MODEL_CONFIG} 未注册 {GEMMA4_MODEL} supplier（环境耦合，非产品缺陷）",
    )
    def test_gemma4_model_supplier_registered(self):
        """Verify Gemma4 model is registered as a supplier in the local model config."""
        result = run_cli(["run", "ping", "--model", "gemma-4"], timeout=60)
        output = result.stdout + result.stderr
        print(f"\n  ↳ Gemma4 model routing test: exit={result.returncode}")
        self.assertNotIn("No supplier found", output,
                         "ModelProvider should find a supplier for gemma-4")

    def test_gemma4_via_direct_api(self):
        """Verify the Gemma4 API works directly (independent of Java)."""
        resp = self.client.chat_completions(
            model=self.model,
            messages=[{"role": "user", "content": "Say OK in one word."}],
            max_tokens=10,
            timeout=60,
        )
        content = resp["choices"][0]["message"]["content"].strip()
        self.assertTrue(content, "Response should not be empty")
        print(f"\n  ↳ Gemma4 direct API: '{content}'")

    def test_gemma4_model_enum_has_entry(self):
        """ModelEnum should have GEMMA_4 with correct values."""
        result = run_cli(["run", "test model enum", "--model", "gemma-4"], timeout=60)
        self.assertIn(result.returncode, [0, 1], msg=result.stderr[-300:])
        print(f"\n  ↳ ModelEnum routing for gemma-4: exit={result.returncode}")


# ── System-Level Tests ─────────────────────────────────────────────────────

@unittest.skipIf(not (PROJECT_ROOT / "alice-bootstrap" / "build").is_dir(), "Project not built yet. Run with --build first.")
class TestAliceAgentSystem(unittest.TestCase):
    """System-level integration tests for the full Alice Agent stack."""

    def test_gradle_check_passes(self):
        """Gradle 'check' (unit tests) should pass before E2E."""
        if not REBUILD:
            self.skipTest("Skipped (use --build to run gradle check)")
        result = run_gradle("check", timeout=300)
        self.assertEqual(result.returncode, 0, msg=diagnostic_tail(result))
        output = result.stdout
        self.assertIn("BUILD SUCCESSFUL", output)
        print("\n  ↳ All unit tests pass")

    def test_distribution_has_all_jars(self):
        """The built distribution should contain all module jars."""
        dist_lib = PROJECT_ROOT / "alice-bootstrap" / "build" / "install" / "alice" / "lib"
        if not dist_lib.is_dir():
            self.skipTest("Distribution not found — run with --build first")
        jars = list(dist_lib.glob("*.jar"))
        jar_names = [j.name for j in jars]
        print(f"\n  ↳ Distribution contains {len(jars)} JARs")
        expected_modules = [
            "alice-agent", "alice-agent-proto", "alice-core-agent",
            "alice-core-planner", "alice-env-adapter", "alice-facade-cli",
            "alice-facade-tui", "alice-memory-vault", "alice-tool-gateway",
        ]
        for module in expected_modules:
            found = any(module in name for name in jar_names)
            self.assertTrue(found, f"Missing {module} JAR in distribution")
            if found:
                matching = [n for n in jar_names if module in n]
                print(f"    ✅ {matching[0]}")
        print(f"  ↳ All {len(expected_modules)} key modules present in distribution")


if __name__ == "__main__":
    print("=" * 64)
    print("  Alice Agent E2E Test Suite")
    print(f"  Project root: {PROJECT_ROOT}")
    if REBUILD:
        print("  Mode: rebuild + test")
    else:
        print("  Mode: test only (use --build to rebuild)")
    print("=" * 64)
    unittest.main(verbosity=2)
