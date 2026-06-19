#!/usr/bin/env python3
"""
Hole Test — alice-env-adapter module endpoints.

See:
  docs/alice-agent-command/e2e/case-env-adapter.md
  docs/alice-env-adapter/e2e/scene-env-endpoints.md
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "..", "e2e"))
from helpers import run_gradle_task, PROJECT_ROOT


class TestEnvAdapterHoles(unittest.TestCase):
    """Hole tests for alice-env-adapter — 4 probes."""

    @classmethod
    def setUpClass(cls):
        cls.build_ok = (PROJECT_ROOT / "alice-env-adapter" / "build").is_dir()

    def test_env_p01_env_manager_execute(self):
        """ENV-P01: EnvManager.execute(Action) returns Observation."""
        if not self.build_ok:
            self.skipTest("Module not built.")
        result = run_gradle_task(":alice-env-adapter:test", "--tests", "*EnvManagerSpec*")
        self.assertEqual(result.returncode, 0,
                         msg=f"ENV-P01 failed: {result.stderr[:200]}")

    def test_env_p02_mcp_client_call_tool(self):
        """ENV-P02: McpClient.callTool() via FakeMcpTransport."""
        if not self.build_ok:
            self.skipTest("Module not built.")
        result = run_gradle_task(":alice-env-adapter:test", "--tests", "*McpClientSpec*")
        self.assertEqual(result.returncode, 0,
                         msg=f"ENV-P02 failed: {result.stderr[:200]}")

    def test_env_p03_mcp_client_list_tools(self):
        """ENV-P03: McpClient.listTools() returns tool list."""
        if not self.build_ok:
            self.skipTest("Module not built.")
        result = run_gradle_task(":alice-env-adapter:test", "--tests", "*FakeTransportSpec*")
        self.assertEqual(result.returncode, 0,
                         msg=f"ENV-P03 failed: {result.stderr[:200]}")

    def test_env_p04_snapshot_rollback(self):
        """ENV-P04: SnapshotManager.save() + rollback() restores state."""
        if not self.build_ok:
            self.skipTest("Module not built.")
        result = run_gradle_task(":alice-env-adapter:test", "--tests", "*SnapshotManagerSpec*")
        self.assertEqual(result.returncode, 0,
                         msg=f"ENV-P04 failed: {result.stderr[:200]}")


if __name__ == "__main__":
    print("=" * 60)
    print("  Hole Test: alice-env-adapter")
    print(f"  Module: {PROJECT_ROOT / 'alice-env-adapter'}")
    print("=" * 60)
    unittest.main(verbosity=2)
