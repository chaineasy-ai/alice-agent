#!/usr/bin/env python3
"""
Hole Test — alice-tool-gateway module endpoints.

See:
  docs/alice-agent-command/e2e/case-tool-gateway.md
  docs/alice-tool-gateway/e2e/scene-tool-gateway-endpoints.md
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "..", "e2e"))
from helpers import run_gradle_task, PROJECT_ROOT


class TestToolGatewayHoles(unittest.TestCase):
    """Hole tests for alice-tool-gateway — 4 probes."""

    @classmethod
    def setUpClass(cls):
        cls.build_ok = (PROJECT_ROOT / "alice-tool-gateway" / "build").is_dir()

    def test_tgw_p01_tool_registry(self):
        """TGW-P01: ToolRegistry.register() + lookup() round-trip."""
        if not self.build_ok:
            self.skipTest("Module not built.")
        result = run_gradle_task(":alice-tool-gateway:test", "--tests", "*ToolRegistrySpec*")
        self.assertEqual(result.returncode, 0,
                         msg=f"TGW-P01 failed: {result.stderr[:200]}")

    def test_tgw_p02_tool_discovery(self):
        """TGW-P02: ToolDiscovery.scanAndRegister() auto-discovery."""
        if not self.build_ok:
            self.skipTest("Module not built.")
        result = run_gradle_task(":alice-tool-gateway:test", "--tests", "*ToolDiscoverySpec*")
        self.assertEqual(result.returncode, 0,
                         msg=f"TGW-P02 failed: {result.stderr[:200]}")

    def test_tgw_p03_execution_engine(self):
        """TGW-P03: ExecutionEngine.invoke() returns Observation."""
        if not self.build_ok:
            self.skipTest("Module not built.")
        result = run_gradle_task(":alice-tool-gateway:test", "--tests", "*ExecutionEngineSpec*")
        self.assertEqual(result.returncode, 0,
                         msg=f"TGW-P03 failed: {result.stderr[:200]}")

    def test_tgw_p04_sandbox_provider(self):
        """TGW-P04: SandboxProvider.executeInIsolation() basic task."""
        if not self.build_ok:
            self.skipTest("Module not built.")
        result = run_gradle_task(":alice-tool-gateway:test", "--tests", "*SandboxProviderSpec*")
        self.assertEqual(result.returncode, 0,
                         msg=f"TGW-P04 failed: {result.stderr[:200]}")


if __name__ == "__main__":
    print("=" * 60)
    print("  Hole Test: alice-tool-gateway")
    print(f"  Module: {PROJECT_ROOT / 'alice-tool-gateway'}")
    print("=" * 60)
    unittest.main(verbosity=2)
