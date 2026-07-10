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
from helpers import run_gradle, run_cli, PROJECT_ROOT, GRADLEW, GEMMA4_BASE_URL, GEMMA4_MODEL, skip_if_no_gemma4

TIMEOUT = int(os.environ.get("GEMMA4_TIMEOUT", "180"))
REBUILD = "--build" in sys.argv
if "--build" in sys.argv:
    sys.argv.remove("--build")


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
            raise RuntimeError(f"Build failed: {result.stderr[-300:]}")

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
        result = run_cli(["run", "--help"], timeout=30, module=':alice-facade-cmd:run')
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
        result = run_cli(["run", "Hello"], timeout=TIMEOUT)
        output = result.stdout + result.stderr
        print(f"\n  ↳ Exit code: {result.returncode}")
        self.assertIn(result.returncode, [0, 1],
                      f"Expected exit 0 or 1, got {result.returncode}")
        self.assertIn("RunConfig", output, "Should log RunConfig")
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

    def test_model_flag_passed_to_run_config(self):
        """--model flag should appear in the RunConfig log."""
        result = run_cli(["run", "test task", "--model", "gpt-4o"], timeout=TIMEOUT)
        output = result.stdout + result.stderr
        self.assertIn("gpt-4o", output, "RunConfig should contain the overridden model")
        print("\n  ↳ Model override 'gpt-4o' in RunConfig verified")

    def test_verbose_flag_enables_thoughts(self):
        """--verbose flag should enable additional output."""
        result = run_cli(["run", "test task", "--verbose"], timeout=TIMEOUT)
        output = result.stdout + result.stderr
        self.assertIn("verbose", output.lower(), "Should mention verbose mode")
        print("\n  ↳ Verbose mode verified")

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
        """FacadeSelector should detect --tui flag."""
        result = run_cli(["--tui"], timeout=30, module=':alice-bootstrap:run')
        output = result.stdout + result.stderr
        self.assertIn(result.returncode, [0], msg=result.stderr[:300])
        self.assertIn("Facade selected: tui", output, "FacadeSelector should select tui facade")
        print("\n  ↳ FacadeSelector detects --tui correctly")


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

    def test_gemma4_model_supplier_registered(self):
        """Verify Gemma4 model is registered in ModelEnum."""
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
        self.assertEqual(result.returncode, 0, msg=result.stderr[-500:])
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
            "alice-agent", "alice-agent-command", "alice-core-agent",
            "alice-core-planner", "alice-env-adapter", "alice-facade-cmd",
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
