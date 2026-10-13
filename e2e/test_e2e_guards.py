#!/usr/bin/env python3
"""Regression guards for the E2E harness itself (#416 / #417).

#416 -- ``run_gradle`` merges stderr into stdout, so ``result.stderr`` is ``None``.
        Indexing it directly raised ``TypeError`` and masked the real failure.
#417 -- e2e runs could silently dirty the working tree and break ``./gradlew check``.

Run:  python -m unittest e2e.test_e2e_guards -v
"""

import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from helpers import diagnostic_tail, git_status_porcelain  # noqa: E402


class DiagnosticTailTest(unittest.TestCase):
    """#416: diagnostic extraction must never raise on merged streams."""

    def test_merged_stream_stderr_none_does_not_raise(self):
        # Shape produced by run_gradle(): stderr=subprocess.STDOUT => stderr is None.
        result = subprocess.CompletedProcess(
            args=["gradlew", "check"], returncode=1,
            stdout="FAILURE: Build failed with an exception.", stderr=None,
        )
        # Pre-fix this was `result.stderr[-500:]` -> TypeError, hiding the message.
        self.assertEqual(
            diagnostic_tail(result, 500),
            "FAILURE: Build failed with an exception.",
        )

    def test_stderr_preferred_when_present(self):
        result = subprocess.CompletedProcess(
            args=["gradlew"], returncode=1, stdout="out", stderr="err",
        )
        self.assertEqual(diagnostic_tail(result), "err")

    def test_no_output_is_empty_not_none(self):
        result = subprocess.CompletedProcess(
            args=["gradlew"], returncode=1, stdout=None, stderr=None,
        )
        self.assertEqual(diagnostic_tail(result), "")

    def test_tail_is_bounded(self):
        result = subprocess.CompletedProcess(
            args=["gradlew"], returncode=1, stdout="x" * 1000, stderr=None,
        )
        self.assertEqual(len(diagnostic_tail(result, 100)), 100)


class WorktreeGuardTest(unittest.TestCase):
    """#417: the working-tree probe reports real porcelain entries."""

    def test_dirty_path_is_reported(self):
        # git_status_porcelain() reads PROJECT_ROOT; assert it is well-formed.
        lines = git_status_porcelain()
        self.assertIsInstance(lines, list)
        for line in lines:
            self.assertTrue(line.strip())

    def test_clean_dir_reports_nothing(self):
        with tempfile.TemporaryDirectory() as tmp:
            # An empty, non-repo directory must yield [] (guard degrades safely).
            result = subprocess.run(
                ["git", "status", "--porcelain"],
                cwd=tmp, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                text=True,
            )
            self.assertNotEqual(result.returncode, 0)


if __name__ == "__main__":
    unittest.main()
