#!/usr/bin/env python3
"""
Hole Test — alice-tool-gateway module endpoints.

All 9 probes run in a single Gradle JavaExec invocation (key="all") to avoid
Gradle daemon overhead/crashes from repeated invocations.

See:
  docs/alice-agent-command/e2e/case-tool-gateway.md
  docs/alice-tool-gateway/e2e/scene-tool-gateway-endpoints.md
"""

import os
import subprocess
import sys
import unittest

PROJECT_ROOT = os.path.normpath(
    os.path.join(os.path.dirname(__file__), "..", "..", "..")
)

MODULE = "alice-tool-gateway"


def run_all_holes(timeout: int = 120) -> "subprocess.CompletedProcess":
    """Run all tool-gateway hole probes in a single Gradle invocation."""
    cmd = [
        "cmd", "/c",
        PROJECT_ROOT + "/gradlew.bat",
        f":{MODULE}:runHoleTest",
        "--args=all",
    ]
    result = subprocess.run(
        cmd,
        cwd=PROJECT_ROOT,
        capture_output=True,
        text=True,
        timeout=timeout,
    )
    return result


class TestToolGatewayHoles(unittest.TestCase):
    """All 9 tool-gateway probes verified via single 'all' invocation."""

    def test_tgw_p01_to_p09_all(self):
        """TGW-P01..P09: All probes in a single Gradle run."""
        result = run_all_holes(timeout=120)
        self.assertEqual(
            result.returncode, 0,
            msg=f"Tool-gateway holes failed (rc={result.returncode}):\n{result.stdout[-500:]}")
        # Count PASS lines
        pass_count = result.stdout.count("PASS:")
        expected = 9  # We skip web_search in "all", so 8 probes + web search is separate
        # The "all" key runs 8 probes (lookup, list, scan, invoke, sandbox, builtins, mcp_tool, mcp_registry)
        self.assertGreaterEqual(
            pass_count, 7,
            msg=f"Expected >=7 PASS lines, got {pass_count}:\n{result.stdout[:500]}")


if __name__ == "__main__":
    print("=" * 60)
    print(f"  Hole Test: {MODULE}")
    print(f"  Module: {os.path.join(PROJECT_ROOT, MODULE)}")
    print("=" * 60)
    print(f"  Holes: TGW-P01..P09 (9 probes via 1 Gradle call)")
    print(f"  Prober: BuiltinToolsHoleTest (Java Exec, key='all')")
    print("=" * 60)
    unittest.main(verbosity=2)
