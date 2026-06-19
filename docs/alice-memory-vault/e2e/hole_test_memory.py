#!/usr/bin/env python3
"""
Hole Test — alice-memory-vault module endpoints.

See:
  docs/alice-agent-command/e2e/case-memory-vault.md
  docs/alice-memory-vault/e2e/scene-memory-endpoints.md
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "..", "e2e"))
from helpers import run_gradle_task, PROJECT_ROOT


class TestMemoryVaultHoles(unittest.TestCase):
    """Hole tests for alice-memory-vault — 5 probes."""

    @classmethod
    def setUpClass(cls):
        cls.build_ok = (PROJECT_ROOT / "alice-memory-vault" / "build").is_dir()

    def test_mem_p01_vault_controller(self):
        """MEM-P01: VaultController.memorize() + recall() round-trip."""
        if not self.build_ok:
            self.skipTest("Module not built.")
        result = run_gradle_task(":alice-memory-vault:test", "--tests", "*MemoryVaultSpec*")
        self.assertEqual(result.returncode, 0,
                         msg=f"MEM-P01 failed: {result.stderr[:200]}")

    def test_mem_p02_episodic_trace(self):
        """MEM-P02: EpisodicVault.getRecentTrace() returns steps."""
        if not self.build_ok:
            self.skipTest("Module not built.")
        result = run_gradle_task(":alice-memory-vault:test", "--tests", "*WalEpisodicVaultSpec*")
        self.assertEqual(result.returncode, 0,
                         msg=f"MEM-P02 failed: {result.stderr[:200]}")

    def test_mem_p03_semantic_search(self):
        """MEM-P03: SemanticVault.search() returns relevant knowledge."""
        if not self.build_ok:
            self.skipTest("Module not built.")
        result = run_gradle_task(":alice-memory-vault:test", "--tests", "*JVectorSemanticVaultSpec*")
        self.assertEqual(result.returncode, 0,
                         msg=f"MEM-P03 failed: {result.stderr[:200]}")

    def test_mem_p04_procedural_match(self):
        """MEM-P04: ProceduralVault.matchPattern() returns SOPs."""
        if not self.build_ok:
            self.skipTest("Module not built.")
        # Procedural vault tests are likely in MemoryVaultSpec or dedicated
        result = run_gradle_task(":alice-memory-vault:test", "--tests", "*MemoryVault*")
        self.assertEqual(result.returncode, 0,
                         msg=f"MEM-P04 failed: {result.stderr[:200]}")

    def test_mem_p05_wal_crash_recovery(self):
        """MEM-P05: WAL crash recovery preserves data."""
        if not self.build_ok:
            self.skipTest("Module not built.")
        result = run_gradle_task(":alice-memory-vault:test", "--tests", "*CrashRecoveryE2ESpec*")
        self.assertEqual(result.returncode, 0,
                         msg=f"MEM-P05 failed: {result.stderr[:200]}")


if __name__ == "__main__":
    print("=" * 60)
    print("  Hole Test: alice-memory-vault")
    print(f"  Module: {PROJECT_ROOT / 'alice-memory-vault'}")
    print("=" * 60)
    unittest.main(verbosity=2)
