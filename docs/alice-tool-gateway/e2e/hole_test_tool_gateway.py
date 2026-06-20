#!/usr/bin/env python3
"""
Hole Test — alice-tool-gateway module endpoints.

Each probe invokes BuiltinToolsHoleTest directly via Gradle JavaExec (runHoleTest),
exercising module boundary without going through unit test runners.

See:
  docs/alice-agent-command/e2e/case-tool-gateway.md
  docs/alice-tool-gateway/e2e/scene-tool-gateway-endpoints.md
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "..", "e2e"))
from helpers import run_gradle_task, PROJECT_ROOT


def run_hole(key: str, *extra_args: str, timeout: int = 30) -> "subprocess.CompletedProcess":
    """Run `runHoleTest` with given key and optional extra args.

    Extra args are passed as independent arguments to --args, e.g.:
        run_hole("web_search", "java programming", "3")
    → Gradle: --args="web_search 'java programming' 3"
    """
    args_list = [key] + list(extra_args)
    # Build a single --args string: key 'arg1' 'arg2'
    quoted = []
    for a in args_list:
        if ' ' in a:
            quoted.append(f"'{a}'")
        else:
            quoted.append(a)
    args_str = " ".join(quoted)
    return run_gradle_task(":alice-tool-gateway:runHoleTest", "--args", args_str, timeout=timeout)


class TestToolGatewayHoles(unittest.TestCase):
    """Hole tests for alice-tool-gateway — direct module boundary calls via runHoleTest."""

    def test_tgw_p01_tool_registry_lookup(self):
        """TGW-P01: ToolRegistry.lookup() — verify all 9 builtin tools are registered."""
        result = run_hole("lookup")
        self.assertEqual(result.returncode, 0,
                         msg=f"TGW-P01 failed: {result.stderr[:200]}")
        self.assertIn("PASS:", result.stdout,
                      msg=f"TGW-P01: unexpected output: {result.stdout[:200]}")

    def test_tgw_p02_tool_discovery_scan(self):
        """TGW-P02: ToolDiscovery.scanAndRegister() — populates registry with 9 tools."""
        result = run_hole("scan")
        self.assertEqual(result.returncode, 0,
                         msg=f"TGW-P02 failed: {result.stderr[:200]}")
        self.assertIn("PASS:", result.stdout,
                      msg=f"TGW-P02: unexpected output: {result.stdout[:200]}")

    def test_tgw_p03_execution_engine_invoke(self):
        """TGW-P03: ExecutionEngine.invoke('list_dir', {path: ...}) returns SUCCESS."""
        result = run_hole("invoke", timeout=90)
        self.assertEqual(result.returncode, 0,
                         msg=f"TGW-P03 failed: {result.stderr[:200]}")
        self.assertIn("PASS:", result.stdout,
                      msg=f"TGW-P03: unexpected output: {result.stdout[:200]}")

    def test_tgw_p04_sandbox_provider(self):
        """TGW-P04: SandboxProvider executes 'run' command through ExecutionEngine."""
        result = run_hole("sandbox", timeout=120)
        self.assertEqual(result.returncode, 0,
                         msg=f"TGW-P04 failed: {result.stderr[:200]}")
        self.assertIn("PASS:", result.stdout,
                      msg=f"TGW-P04: unexpected output: {result.stdout[:200]}")

    def test_tgw_p05_tool_list_query(self):
        """TGW-P05: ToolRegistry.toolNames() / allTools() — consistent counts."""
        result = run_hole("list")
        self.assertEqual(result.returncode, 0,
                         msg=f"TGW-P05 failed: {result.stderr[:200]}")
        self.assertIn("PASS:", result.stdout,
                      msg=f"TGW-P05: unexpected output: {result.stdout[:200]}")

    def test_tgw_p06_builtin_tools(self):
        """TGW-P06: All 9 BuiltinTools methods (read_file, write_file, grep, run, list_dir, file_exists, search_file, remove_file)."""
        result = run_hole("builtins")
        self.assertEqual(result.returncode, 0,
                         msg=f"TGW-P06 failed: {result.stderr[:200]}")
        self.assertIn("PASS:", result.stdout,
                      msg=f"TGW-P06: unexpected output: {result.stdout[:200]}")

    def test_tgw_p07_web_search_integration(self):
        """TGW-P07: BuiltinTools.webSearch() — real DuckDuckGo call (SKIP if no network)."""
        result = run_hole("web_search", "java programming", "3", timeout=30)
        if result.returncode != 0:
            self.fail(f"TGW-P07 failed: {result.stderr[:200]}")
        if "SKIP:" in result.stdout:
            self.skipTest("Network unavailable for web_search hole test")
        self.assertIn("PASS:", result.stdout,
                      msg=f"TGW-P07: unexpected output: {result.stdout[:200]}")


if __name__ == "__main__":
    print("=" * 60)
    print("  Hole Test: alice-tool-gateway")
    print(f"  Module: {PROJECT_ROOT / 'alice-tool-gateway'}")
    print("=" * 60)
    unittest.main(verbosity=2)
