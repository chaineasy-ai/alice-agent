#!/usr/bin/env python3
"""
Hole Test — alice-env-adapter module endpoints.

Each probe invokes EnvAdapterHoleTest directly via Gradle JavaExec (runHoleTest),
exercising module boundary without going through unit test runners.

See:
  docs/alice-agent-command/e2e/case-env-adapter.md
  docs/alice-env-adapter/e2e/scene-env-adapter-endpoints.md
"""

import os
import subprocess
import sys
import unittest

PROJECT_ROOT = os.path.normpath(
    os.path.join(os.path.dirname(__file__), "..", "..", "..")
)

MODULE = "alice-env-adapter"


def run_hole(key: str, timeout: int = 30) -> "subprocess.CompletedProcess":
    cmd = [
        "cmd", "/c",
        "gradlew.bat",
        f":{MODULE}:runHoleTest",
        f"--args={key}",
    ]
    result = subprocess.run(
        cmd,
        cwd=PROJECT_ROOT,
        capture_output=True,
        text=True,
        timeout=timeout,
    )
    return result


class TestEnvAdapterHoles(unittest.TestCase):
    """Hole tests for alice-env-adapter — 5 probes via Java EnvAdapterHoleTest."""

    def test_env_p01_env_state(self):
        """ENV-P01: EnvState state machine."""
        result = run_hole("envState")
        self.assertEqual(
            result.returncode, 0,
            msg=f"ENV-P01 failed: {result.stderr[-200:]}")
        self.assertIn(
            "PASS:", result.stdout,
            msg=f"ENV-P01: unexpected output: {result.stdout[-200:]}")

    def test_env_p02_env_snapshot(self):
        """ENV-P02: EnvSnapshot builder and methods."""
        result = run_hole("envSnapshot")
        self.assertEqual(
            result.returncode, 0,
            msg=f"ENV-P02 failed: {result.stderr[-200:]}")
        self.assertIn(
            "PASS:", result.stdout,
            msg=f"ENV-P02: unexpected output: {result.stdout[-200:]}")

    def test_env_p03_snapshot_manager(self):
        """ENV-P03: SnapshotManager save/rollback/clear."""
        result = run_hole("snapshotManager")
        self.assertEqual(
            result.returncode, 0,
            msg=f"ENV-P03 failed: {result.stderr[-200:]}")
        self.assertIn(
            "PASS:", result.stdout,
            msg=f"ENV-P03: unexpected output: {result.stdout[-200:]}")

    def test_env_p04_mcp_client(self):
        """ENV-P04: McpClient / Tool model."""
        result = run_hole("mcpClient")
        self.assertEqual(
            result.returncode, 0,
            msg=f"ENV-P04 failed: {result.stderr[-200:]}")
        self.assertIn(
            "PASS:", result.stdout,
            msg=f"ENV-P04: unexpected output: {result.stdout[-200:]}")

    def test_env_p05_mcp_transport(self):
        """ENV-P05: McpTransport interface via FakeMcpTransport."""
        result = run_hole("mcpTransport")
        self.assertEqual(
            result.returncode, 0,
            msg=f"ENV-P05 failed: {result.stderr[-200:]}")
        self.assertIn(
            "PASS:", result.stdout,
            msg=f"ENV-P05: unexpected output: {result.stdout[-200:]}")


if __name__ == "__main__":
    print("=" * 60)
    print(f"  Hole Test: {MODULE}")
    print(f"  Module: {os.path.join(PROJECT_ROOT, MODULE)}")
    print("=" * 60)
    print(f"  Holes: ENV-P01..P05 (5 probes)")
    print(f"  Prober: EnvAdapterHoleTest (Java Exec)")
    print("=" * 60)
    unittest.main(verbosity=2)
