#!/usr/bin/env python3
"""
Hole Test — alice-model module endpoints.

See:
  docs/alice-agent-command/e2e/case-model.md
  docs/alice-model/e2e/scene-model-endpoints.md
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "..", "e2e"))
from helpers import run_gradle_task, PROJECT_ROOT


class TestModelHoles(unittest.TestCase):
    """Hole tests for alice-model — 5 probes."""

    @classmethod
    def setUpClass(cls):
        cls.build_ok = (PROJECT_ROOT / "alice-model" / "build").is_dir()

    def test_mdl_p01_provider_dispatch(self):
        """MDL-P01: ModelProvider.dispatch() with FakeSupplier."""
        if not self.build_ok:
            self.skipTest("Module not built.")
        result = run_gradle_task(":alice-model:test", "--tests", "*ModelProviderSpec*")
        self.assertEqual(result.returncode, 0,
                         msg=f"MDL-P01 failed: {result.stderr[:200]}")

    def test_mdl_p02_call_lifecycle(self):
        """MDL-P02: Call.execute() lifecycle NEW→RUNNING→DONE."""
        if not self.build_ok:
            self.skipTest("Module not built.")
        result = run_gradle_task(":alice-model:test", "--tests", "*CallSpec*")
        self.assertEqual(result.returncode, 0,
                         msg=f"MDL-P02 failed: {result.stderr[:200]}")

    def test_mdl_p03_supplier_parse(self):
        """MDL-P03: ModelSupplier.chat() response parsing."""
        if not self.build_ok:
            self.skipTest("Module not built.")
        result = run_gradle_task(":alice-model:test", "--tests", "*ClaudeSupplierSpec*")
        self.assertEqual(result.returncode, 0,
                         msg=f"MDL-P03 failed: {result.stderr[:200]}")

    def test_mdl_p04_config_loader(self):
        """MDL-P04: ModelConfigLoader.loadConfig() from JSON."""
        if not self.build_ok:
            self.skipTest("Module not built.")
        result = run_gradle_task(":alice-model:test", "--tests", "*ModelConfigLoaderSpec*")
        self.assertEqual(result.returncode, 0,
                         msg=f"MDL-P04 failed: {result.stderr[:200]}")

    def test_mdl_p05_multi_routing(self):
        """MDL-P05: Multi-supplier routing by modelId."""
        if not self.build_ok:
            self.skipTest("Module not built.")
        result = run_gradle_task(":alice-model:test", "--tests", "*ModelProviderSpec*")
        self.assertEqual(result.returncode, 0,
                         msg=f"MDL-P05 failed: {result.stderr[:200]}")


if __name__ == "__main__":
    print("=" * 60)
    print("  Hole Test: alice-model")
    print(f"  Module: {PROJECT_ROOT / 'alice-model'}")
    print("=" * 60)
    unittest.main(verbosity=2)
