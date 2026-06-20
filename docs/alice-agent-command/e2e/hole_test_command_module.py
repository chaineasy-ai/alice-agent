#!/usr/bin/env python3
"""
Hole tests for alice-agent-command module.

Probes the module boundary through dedicated Java hole test classes
in the `src/hole/java/` source set, invoked via Gradle JavaExec (runHoleTest).

PRINCIPLES:
  - Each hole tests exactly 1 happy inbound business case.
  - No duplication of existing Spock unit tests (src/test/groovy).
  - No edge cases (null, empty, invalid) — those belong in unit tests.
  - Each assertion probes a boundary not covered by Spock.

HOLE INVENTORY:
  CMD-P01: Module classpath resolves all 21 sealed types via Class.forName()
  CMD-P02: AgentCommand.parse() dispatch pipeline (1 per branch, 6 total)

Usage:
    python docs/alice-agent-command/e2e/hole_test_command_module.py
"""

import subprocess
import sys
import os
import re
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[3]
MODULE = ":alice-agent-command"


def run_gradle_task(*args, timeout=120):
    """Run a Gradle task and return the CompletedProcess."""
    gradlew = (
        str(PROJECT_ROOT / "gradlew.bat")
        if sys.platform == "win32"
        else str(PROJECT_ROOT / "gradlew")
    )
    cmd = [gradlew] + list(args)
    result = subprocess.run(
        cmd,
        cwd=str(PROJECT_ROOT),
        capture_output=True,
        text=True,
        timeout=timeout,
    )
    return result


def run_hole_and_capture(hole_id: str, timeout: int = 60):
    """Run a single hole probe and return structured log lines extracted from output.

    Returns:
        result: subprocess.CompletedProcess
        item_lines: list of (index, total, description, status) for ITEM lines
        final_lines: list of (type, message) for PASS/FAIL lines
    """
    result = run_gradle_task(
        MODULE + ":runHoleTest",
        "--args",
        hole_id,
        timeout=timeout,
    )

    combined = (result.stdout + "\n" + result.stderr).splitlines()

    item_lines = []   # (index, total, desc, status)
    final_lines = []  # (type, msg)

    item_pat = re.compile(
        r"CMD-P0\d:\s+ITEM\s+(\d+)/(\d+)\s+::\s+(.*?)\s+::\s+(OK|FAIL\(.*\))"
    )
    pass_pat = re.compile(r"CMD-P0\d:\s+PASS\s+::\s+(.*)")
    fail_pat = re.compile(r"CMD-P0\d:\s+FAIL\s+::\s+(.*)")

    for line in combined:
        line = line.strip()
        m = item_pat.search(line)
        if m:
            item_lines.append((int(m.group(1)), int(m.group(2)), m.group(3), m.group(4)))
            continue
        m = pass_pat.search(line)
        if m:
            final_lines.append(("PASS", m.group(1)))
            continue
        m = fail_pat.search(line)
        if m:
            final_lines.append(("FAIL", m.group(1)))
            continue

    return result, item_lines, final_lines


class HoleTestResult:
    """Collects and prints hole test results."""

    def __init__(self):
        self.passed = []
        self.failed = []
        self.skipped = []

    def pass_hole(self, hole_id: str, detail: str = ""):
        self.passed.append((hole_id, detail))

    def fail_hole(self, hole_id: str, detail: str = ""):
        self.failed.append((hole_id, detail))

    def skip_hole(self, hole_id: str, reason: str = ""):
        self.skipped.append((hole_id, reason))

    def print_summary(self):
        print(f"\n{'='*60}")
        print(f"HOLE TEST SUMMARY — alice-agent-command")
        print(f"{'='*60}")
        for hole_id, detail in self.passed:
            print(f"  ✅  {hole_id}: PASS — {detail}")
        for hole_id, detail in self.failed:
            print(f"  ❌  {hole_id}: FAIL — {detail}")
        for hole_id, reason in self.skipped:
            print(f"  ⏭️   {hole_id}: SKIP — {reason}")
        print(f"{'='*60}")
        print(f"  Total: {len(self.passed)} passed, "
              f"{len(self.failed)} failed, "
              f"{len(self.skipped)} skipped")
        print(f"{'='*60}")

    @property
    def success(self) -> bool:
        return len(self.failed) == 0


# =========================================================================
# HOLE: CMD-P01 — Module classpath resolves all 21 sealed types
# =========================================================================

CMD_P01_EXPECTED_ITEMS = 21

def test_cmd_p01_module_classpath(collector: HoleTestResult):
    """CMD-P01: All 21 concrete record classes resolve via Class.forName()."""
    result, item_lines, final_lines = run_hole_and_capture("CMD-P01")
    failures = []

    if len(item_lines) != CMD_P01_EXPECTED_ITEMS:
        failures.append(
            f"expected {CMD_P01_EXPECTED_ITEMS} ITEM lines, got {len(item_lines)}"
        )
    else:
        ok_count = sum(1 for _, _, _, s in item_lines if s == "OK")
        if ok_count != CMD_P01_EXPECTED_ITEMS:
            failures.append(
                f"{CMD_P01_EXPECTED_ITEMS - ok_count} items FAILED out of {CMD_P01_EXPECTED_ITEMS}"
            )
            for idx, tot, desc, status in item_lines:
                if status != "OK":
                    print(f"  [CMD-P01] ITEM {idx}/{tot}: {desc} — {status}")

    if not any(t == "PASS" for t, _ in final_lines):
        failures.append("no final PASS line in output")

    if failures:
        collector.fail_hole("CMD-P01", "; ".join(failures))
    else:
        collector.pass_hole(
            "CMD-P01",
            f"{CMD_P01_EXPECTED_ITEMS}/{CMD_P01_EXPECTED_ITEMS} items OK, "
            f"all 21 sealed types resolve on module path"
        )


# =========================================================================
# HOLE: CMD-P02 — Parse dispatch pipeline (1 per branch)
# =========================================================================

CMD_P02_EXPECTED_ITEMS = 6

def test_cmd_p02_dispatch_pipeline(collector: HoleTestResult):
    """CMD-P02: AgentCommand.parse() dispatches all 6 branches in a standalone JVM."""
    result, item_lines, final_lines = run_hole_and_capture("CMD-P02")
    failures = []

    if len(item_lines) != CMD_P02_EXPECTED_ITEMS:
        failures.append(
            f"expected {CMD_P02_EXPECTED_ITEMS} ITEM lines, got {len(item_lines)}"
        )
    else:
        ok_count = sum(1 for _, _, _, s in item_lines if s == "OK")
        if ok_count != CMD_P02_EXPECTED_ITEMS:
            failures.append(
                f"{CMD_P02_EXPECTED_ITEMS - ok_count} items FAILED out of {CMD_P02_EXPECTED_ITEMS}"
            )
            for idx, tot, desc, status in item_lines:
                if status != "OK":
                    print(f"  [CMD-P02] ITEM {idx}/{tot}: {desc} — {status}")

    if not any(t == "PASS" for t, _ in final_lines):
        failures.append("no final PASS line in output")

    if failures:
        collector.fail_hole("CMD-P02", "; ".join(failures))
    else:
        collector.pass_hole(
            "CMD-P02",
            f"{CMD_P02_EXPECTED_ITEMS}/{CMD_P02_EXPECTED_ITEMS} items OK, "
            f"all 6 branches dispatch in standalone JVM"
        )


# =========================================================================
# Main runner
# =========================================================================

def main():
    collector = HoleTestResult()

    # Phase 1: build the module (includes src/hole/java compilation)
    print("Building alice-agent-command module (including hole source set)...")
    build_result = run_gradle_task(MODULE + ":build", MODULE + ":holeClasses", timeout=120)
    if build_result.returncode != 0:
        print(f"BUILD FAILED:\n{build_result.stdout}\n{build_result.stderr}")
        collector.fail_hole("BUILD", f"Module build failed: {build_result.returncode}")
        collector.print_summary()
        return 1

    print("Build OK. Running hole tests via Gradle JavaExec...\n")

    # Phase 2: run each hole probe — verify every ITEM line + final PASS
    test_cmd_p01_module_classpath(collector)
    test_cmd_p02_dispatch_pipeline(collector)

    collector.print_summary()
    return 0 if collector.success else 1


if __name__ == "__main__":
    sys.exit(main())
