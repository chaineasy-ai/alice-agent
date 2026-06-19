#!/usr/bin/env python3
"""
Hole Test — alice-bootstrap module endpoints.

See:
  docs/alice-agent-command/e2e/case-bootstrap.md
  docs/alice-bootstrap/e2e/scene-bootstrap-endpoints.md
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "..", "e2e"))
from helpers import run_gradle_task, PROJECT_ROOT


class TestBootstrapHoles(unittest.TestCase):
    """Hole tests for alice-bootstrap — 3 probes."""

    @classmethod
    def setUpClass(cls):
        cls.build_ok = (PROJECT_ROOT / "alice-bootstrap" / "build").is_dir()

    def test_bts_p01_facade_selector(self):
        """BTS-P01: FacadeSelector.select() routes to correct launcher."""
        if not self.build_ok:
            self.skipTest("Module not built.")
        result = run_gradle_task(":alice-bootstrap:test",
                                  "--tests", "*AliceAgentSpec*")
        self.assertEqual(result.returncode, 0,
                         msg=f"BTS-P01 failed: {result.stderr[:200]}")

    def test_bts_p02_bootstrapper_lifecycle(self):
        """BTS-P02: AppBootstrapper.bootstrap() completes normally."""
        if not self.build_ok:
            self.skipTest("Module not built.")
        # Test that running alice with 'run' subcommand doesn't crash
        result = run_gradle_task(":alice-bootstrap:test",
                                  "--tests", "*CommandDispatchLoopSpec*")
        self.assertEqual(result.returncode, 0,
                         msg=f"BTS-P02 failed: {result.stderr[:200]}")

    def test_bts_p03_launcher_interface(self):
        """BTS-P03: IFacadeLauncher contract — CLI + TUI implement."""
        if not self.build_ok:
            self.skipTest("Module not built.")
        result = run_gradle_task(":alice-bootstrap:test")
        self.assertEqual(result.returncode, 0,
                         msg=f"BTS-P03 failed: {result.stderr[:200]}")


if __name__ == "__main__":
    print("=" * 60)
    print("  Hole Test: alice-bootstrap")
    print(f"  Module: {PROJECT_ROOT / 'alice-bootstrap'}")
    print("=" * 60)
    unittest.main(verbosity=2)
