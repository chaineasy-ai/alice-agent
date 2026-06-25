#!/usr/bin/env python3
"""
E2E Test — Alice Agent `resume` command

Tests the `alice resume` subcommand which restores a historical session
from persistent storage (WAL/snapshot), rebuilding the context window,
short-term memory, and associated snapshot/branch state.

TC Coverage (from docs/alice-agent-command/e2e/case-resume.md):
  TC-RESUME-01: Resume by --session-id
  TC-RESUME-02: Resume with --snapshot
  TC-RESUME-03: List sessions (--list)
  TC-RESUME-04: Unknown session-id (graceful error)
  TC-RESUME-05: --help output
"""
import os
import sys
import unittest
import subprocess
import time

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "..", "e2e"))
from helpers import run_cli, PROJECT_ROOT

# ========================================================================
# Constants
# ========================================================================
TIMEOUT_SHORT = 30
TIMEOUT_LONG = 120


def extract_output(result):
    """Extract application output lines (filter Gradle noise)."""
    combined = result.stdout + result.stderr
    lines = []
    for line in combined.split('\n'):
        s = line.strip()
        if not s:
            continue
        if any(x in s for x in [
            '> Task', 'BUILD', 'Consider enabling', 'WARNING:', 'Incubating',
            'problems', 'Received shutdown', 'alice-facade-cmd', 'stty:'
        ]):
            continue
        lines.append(s)
    return '\n'.join(lines)


def filter_field_line(output, field):
    """Get line containing a specific RunConfig field."""
    for line in output.split('\n'):
        if field in line:
            return line.strip()
    return None


# ========================================================================
# Pre-requisite check
# ========================================================================
needs_build = not (PROJECT_ROOT / "alice-bootstrap" / "build").is_dir()


@unittest.skipIf(needs_build, "Project not built yet.")
class TestResumeCommand(unittest.TestCase):
    """E2E tests for `alice resume` subcommand."""

    maxDiff = None

    # ── TC-RESUME-01: Resume by session-id ─────────────────────────

    def test_resume_01_by_session_id(self):
        """TC-RESUME-01: `alice resume --session-id <id>` restores session from WAL."""
        test_session = "e2e-resume-001"
        result = run_cli(["resume", "--session-id", test_session], timeout=TIMEOUT_LONG)
        output = extract_output(result)
        self.assertIn(result.returncode, [0, 1],
                       "Resume command may succeed or fail (session may not exist)")
        # Must show RunConfig with resumeMode=true and the provided sessionId
        self.assertIn("resumeMode=true", output,
                       "RunConfig must show resumeMode=true")
        self.assertIn(f"sessionId='{test_session}'", output,
                       "RunConfig must show the provided session ID")
        # Must show session restoration attempt
        self.assertIn("WAL storage", output,
                       "Resume command must attempt WAL storage access")
        print(f"  ✅ [RESUME-01] resume --session-id: resumeMode=true + WAL access (exit={result.returncode})")

    # ── TC-RESUME-02: Resume with snapshot ──────────────────────────

    def test_resume_02_with_snapshot(self):
        """TC-RESUME-02: `alice resume --session-id <id> --snapshot <snap>` passes both through RunConfig."""
        test_session = "e2e-resume-snap-001"
        test_snapshot = "snap-e2e-001"
        result = run_cli(["resume", "--session-id", test_session,
                           "--snapshot", test_snapshot], timeout=TIMEOUT_LONG)
        output = extract_output(result)
        self.assertIn(result.returncode, [0, 1])
        self.assertIn("resumeMode=true", output,
                       "RunConfig must show resumeMode=true")
        self.assertIn(f"sessionId='{test_session}'", output,
                       "RunConfig must show the provided session ID")
        self.assertIn(f"resumeSnapshot='{test_snapshot}'", output,
                       "RunConfig must show the provided snapshot ID")
        self.assertIn("WAL storage", output,
                       "Resume with snapshot must attempt WAL storage access")
        print(f"  ✅ [RESUME-02] resume --session-id + --snapshot: fields verified (exit={result.returncode})")

    # ── TC-RESUME-03: List sessions ─────────────────────────────────

    def test_resume_03_list_sessions(self):
        """TC-RESUME-03: `alice resume --list` shows available sessions."""
        result = run_cli(["resume", "--list"], timeout=TIMEOUT_LONG)
        output = extract_output(result)
        self.assertIn(result.returncode, [0, 1])
        # --list flag should be reflected in RunConfig
        self.assertIn("resumeList=true", output,
                       "RunConfig must show resumeList=true")
        # When no sessions exist, should show empty listing message
        self.assertIn("No sessions available.", output,
                       "Must show empty session list message when no sessions exist")
        print(f"  ✅ [RESUME-03] resume --list: resumeList=true, empty list shown (exit={result.returncode})")

    # ── TC-RESUME-04: Unknown session-id ────────────────────────────

    def test_resume_04_unknown_session(self):
        """TC-RESUME-04: `alice resume --session-id nonexistent` exits with error."""
        result = run_cli(["resume", "--session-id", "nonexistent-999"], timeout=TIMEOUT_LONG)
        output = extract_output(result)
        # Session not found should be a runtime error
        self.assertIn(result.returncode, [0, 1],
                       "Missing session is a runtime error (exit 1) or handled gracefully (exit 0)")
        # Must show error indicating session not found
        err_line = filter_field_line(output, "not found")
        self.assertIsNotNone(err_line,
                              "Must show error message about session not found")
        self.assertIn("not found", err_line.lower(),
                       "Error message must contain 'not found'")
        print(f"  ✅ [RESUME-04] resume --session-id nonexistent: error reported (exit={result.returncode})")

    # ── TC-RESUME-05: Help output ───────────────────────────────────

    def test_resume_05_help(self):
        """TC-RESUME-05: `alice resume --help` shows picocli help, not Gradle help."""
        result = run_cli(["resume", "--help"])
        output = extract_output(result)
        self.assertEqual(result.returncode, 0)
        self.assertIn("Usage:", output, "Must show picocli usage")
        # picocli truncates long options to fit terminal width; check for partial match
        self.assertIn("session", output, "Must show --session-id option or abbreviation")
        self.assertIn("snapshot", output, "Must show --snapshot option")
        self.assertIn("list", output, "Must show --list option")
        self.assertNotIn("PPAO loop", output, "Help must NOT enter PPAO")
        self.assertNotIn("Configuration cache", output, "Must NOT be Gradle help")
        print(f"  ✅ [RESUME-05] resume --help: picocli help with all options")

    # ── TC-RESUME-06 (bonus): Resume by snapshot only ───────────────

    def test_resume_06_snapshot_only(self):
        """TC-RESUME-06: `alice resume --snapshot <snap>` without --session-id works with auto-detection."""
        test_snapshot = "snap-e2e-auto-001"
        result = run_cli(["resume", "--snapshot", test_snapshot], timeout=TIMEOUT_LONG)
        output = extract_output(result)
        self.assertIn(result.returncode, [0, 1])
        # Without --session-id, the system should auto-detect the session from snapshot metadata
        self.assertIn("resumeMode=true", output,
                       "RunConfig must show resumeMode=true")
        self.assertIn(f"resumeSnapshot='{test_snapshot}'", output,
                       "RunConfig must show the provided snapshot ID")
        # sessionId should still be present (auto-detected from snapshot)
        session_line = filter_field_line(output, "sessionId=")
        self.assertIsNotNone(session_line,
                              "sessionId must be present (auto-detected from snapshot)")
        print(f"  ✅ [RESUME-06] resume --snapshot only: auto-detected sessionId (exit={result.returncode})")


# ========================================================================
# Summary Runner
# ========================================================================

if __name__ == "__main__":
    print("=" * 70)
    print("  E2E: Alice Agent resume command")
    print(f"  Project root: {PROJECT_ROOT}")
    print()
    print("  TC Coverage:")
    print("  [RESUME-01] resume --session-id <id>")
    print("  [RESUME-02] resume --session-id --snapshot")
    print("  [RESUME-03] resume --list")
    print("  [RESUME-04] resume --session-id nonexistent")
    print("  [RESUME-05] resume --help")
    print("  [RESUME-06] resume --snapshot only (auto-detect)")
    print()
    print(f"  Run: ./gradlew :alice-facade-cmd:run --args 'resume ...'")
    print("=" * 70)

    loader = unittest.TestLoader()
    suite = unittest.TestSuite()
    suite.addTests(loader.loadTestsFromTestCase(TestResumeCommand))

    runner = unittest.TextTestRunner(verbosity=2)
    result = runner.run(suite)

    print()
    print("=" * 70)
    print("  SUMMARY")
    print(f"  Tests run:    {result.testsRun}")
    print(f"  Passed:       {result.testsRun - len(result.failures) - len(result.errors)}")
    print(f"  Failures:     {len(result.failures)}")
    print(f"  Errors:       {len(result.errors)}")
    print(f"  Skipped:      {len(result.skipped)}")
    if result.failures or result.errors:
        for test, trace in result.failures + result.errors:
            print(f"  ❌ {test.id()}")
    print("=" * 70)

    sys.exit(0 if result.wasSuccessful() else 1)
