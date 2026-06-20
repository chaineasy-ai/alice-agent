#!/usr/bin/env python3
"""
Hole Test — alice-memory-vault module endpoints.

Directly invokes MemoryVaultHoleTest (Java) via Gradle runHoleTest task.
Each probe instantiates module public API classes at the module boundary.

See:
  docs/alice-agent-command/e2e/case-memory-vault.md
  docs/alice-memory-vault/e2e/scene-memory-endpoints.md
  docs/alice-memory-vault/inbound.md
"""

import os
import subprocess
import sys
import unittest

# Project root detection
PROJECT_ROOT = os.path.normpath(
    os.path.join(os.path.dirname(__file__), "..", "..", "..")
)


def run_hole(*args, timeout=30):
    """Run a single hole test probe via Gradle."""
    cmd = [
        "cmd", "/c",
        "gradlew.bat",
        ":alice-memory-vault:runHoleTest",
        f"--args={' '.join(args)}",
    ]
    result = subprocess.run(
        cmd,
        cwd=PROJECT_ROOT,
        capture_output=True,
        text=True,
        timeout=timeout,
    )
    return result


class TestMemoryVaultHoles(unittest.TestCase):
    """Hole tests for alice-memory-vault — 5 probes via Java MemoryVaultHoleTest."""

    def test_mem_p01_vault_controller(self):
        """MEM-P01: VaultController.memorize() + recall() round-trip."""
        result = run_hole("mem_ctrl")
        self.assertEqual(
            result.returncode, 0,
            msg=f"MEM-P01 failed: {result.stdout[:200]}"
        )
        self.assertIn(
            "PASS:", result.stdout,
            msg=f"MEM-P01: unexpected output: {result.stdout[:200]}"
        )

    def test_mem_p02_episodic_trace(self):
        """MEM-P02: EpisodicVault trace append, query, penalty."""
        result = run_hole("episodic")
        self.assertEqual(
            result.returncode, 0,
            msg=f"MEM-P02 failed: {result.stdout[:200]}"
        )
        self.assertIn(
            "PASS:", result.stdout,
            msg=f"MEM-P02: unexpected output: {result.stdout[:200]}"
        )

    def test_mem_p03_semantic_search(self):
        """MEM-P03: SemanticVault store, search, cross-collection search."""
        result = run_hole("semantic")
        self.assertEqual(
            result.returncode, 0,
            msg=f"MEM-P03 failed: {result.stdout[:200]}"
        )
        self.assertIn(
            "PASS:", result.stdout,
            msg=f"MEM-P03: unexpected output: {result.stdout[:200]}"
        )

    def test_mem_p04_procedural_match(self):
        """MEM-P04: ProceduralVault register, match, findByTool."""
        result = run_hole("procedural")
        self.assertEqual(
            result.returncode, 0,
            msg=f"MEM-P04 failed: {result.stdout[:200]}"
        )
        self.assertIn(
            "PASS:", result.stdout,
            msg=f"MEM-P04: unexpected output: {result.stdout[:200]}"
        )

    def test_mem_p05_wal_crash_recovery(self):
        """MEM-P05: FileWalStore append, read, crash recovery."""
        result = run_hole("wal")
        self.assertEqual(
            result.returncode, 0,
            msg=f"MEM-P05 failed: {result.stdout[:200]}"
        )
        self.assertIn(
            "PASS:", result.stdout,
            msg=f"MEM-P05: unexpected output: {result.stdout[:200]}"
        )


if __name__ == "__main__":
    print("=" * 60)
    print("  Hole Test: alice-memory-vault")
    print(f"  Module: {os.path.join(PROJECT_ROOT, 'alice-memory-vault')}")
    print("=" * 60)
    unittest.main(verbosity=2)
