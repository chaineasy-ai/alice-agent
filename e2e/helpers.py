#!/usr/bin/env python3
"""
Alice Agent E2E / Hole Test Shared Utilities.

Pure helper functions for:
  - Running Gradle tasks (core module tests, hole tests)
  - Running CLI commands via Gradle
  - Running Java classes directly
  - Checking external service availability (Gemma4 API)

Usage (hole tests — docs/*/e2e/hole_test_*.py):
    from helpers import run_gradle_task, PROJECT_ROOT
    result = run_gradle_task(":alice-guardrail:test")

Usage (E2E suite — e2e/alice_agent_e2e.py):
    from helpers import run_cli, run_gradle, skip_if_no_gemma4
"""

import os
import subprocess
import sys
from pathlib import Path

# ── Configuration ──────────────────────────────────────────────────────────

PROJECT_ROOT = Path(__file__).resolve().parents[1]

if sys.platform.startswith("win"):
    GRADLEW = str(PROJECT_ROOT / "gradlew.bat")
else:
    GRADLEW = str(PROJECT_ROOT / "gradlew")

GEMMA4_BASE_URL = os.environ.get("GEMMA4_BASE_URL", "http://192.168.1.14:10303/v1")
GEMMA4_MODEL = os.environ.get("GEMMA4_MODEL", "gemma-4")


# ── Gradle Helpers ─────────────────────────────────────────────────────────


def run_gradle_task(task: str, *extra_args, timeout: int = 300) -> subprocess.CompletedProcess:
    """Run a Gradle task with optional extra args. Used by hole tests.

    Usage:
        run_gradle_task(":alice-core-agent:test", "--tests", "*AgentPpaoLoopSpec*")
        run_gradle_task(":alice-core-agent:test", timeout=600)
    """
    return run_gradle(task, *extra_args, timeout=timeout)


def run_gradle(task: str, *extra_args, timeout: int = 300) -> subprocess.CompletedProcess:
    """Run a Gradle task and return the result."""
    cmd = [GRADLEW, task]
    if extra_args:
        cmd.extend(extra_args)
    print(f"\n  ⚙️  Gradle: {' '.join(cmd)}")
    result = subprocess.run(
        cmd,
        cwd=PROJECT_ROOT,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        timeout=timeout,
    )
    if result.returncode != 0:
        print(f"  ❌ Gradle failed:\n{diagnostic_tail(result)}")
    else:
        print(f"  ✅ Gradle {task} succeeded")
    return result


def diagnostic_tail(result: subprocess.CompletedProcess, length: int = 500) -> str:
    """Return the last ``length`` chars of diagnostic output for a CompletedProcess.

    ``run_gradle`` merges stderr into stdout (``stderr=subprocess.STDOUT``), so
    ``result.stderr`` is ``None``.  Indexing it directly (``result.stderr[-500:]``)
    raises ``TypeError`` and masks the real failure.  Always route through this
    helper so the original message survives (#416).
    """
    return (result.stderr or result.stdout or "")[-length:]


# ── Working-Tree Isolation Guard (#417) ────────────────────────────────────


def git_status_porcelain() -> list[str]:
    """Return ``git status --porcelain`` entries for PROJECT_ROOT.

    Returns ``[]`` when git is unavailable, the directory is not a repo, or
    the command fails -- callers treat that as "cannot determine / clean".
    """
    try:
        result = subprocess.run(
            ["git", "status", "--porcelain"],
            cwd=PROJECT_ROOT,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            timeout=30,
        )
    except (OSError, subprocess.SubprocessError):
        return []
    if result.returncode != 0:
        return []
    return [line for line in result.stdout.splitlines() if line.strip()]


def worktree_guard_enabled() -> bool:
    """Whether the e2e worktree-clean gate is active (default: on).

    Set ``ALICE_E2E_ALLOW_DIRTY=1`` to bypass it for local debugging.
    """
    return os.environ.get("ALICE_E2E_ALLOW_DIRTY") != "1"


# ── CLI Helpers ────────────────────────────────────────────────────────────


def build_cli_command(args: list[str], module: str = ':alice-facade-cli:run') -> list[str]:
    """Build a subprocess command to run the CLI via Gradle.

    Handles Windows argument quoting where Gradle's --args needs
    the value to be a single shell-token. Args starting with '--'
    must be quoted to prevent Gradle from intercepting them.
    Empty args use --help to show usage info.

    Args:
        args: CLI arguments to pass to the application
        module: Gradle task to run (default: :alice-facade-cli:run)
    """
    if not args:
        if sys.platform.startswith("win"):
            return [GRADLEW, module, "--args", '"--help"']
        else:
            return [GRADLEW, module, "--args", "--help"]

    if sys.platform.startswith("win"):
        quoted = []
        for a in args:
            if not a:
                quoted.append('""')
            elif ' ' in a or '/' in a or '?' in a or a.startswith('--'):
                quoted.append(f'"{a}"')
            else:
                quoted.append(a)
        args_str = " ".join(quoted)
        return [GRADLEW, module, "--args", args_str]
    else:
        args_str = " ".join(
            f"'{a}'" if (' ' in a or a.startswith('--')) else a
            for a in args
        )
        return [GRADLEW, module, "--args", args_str]


def run_cli(args: list[str], timeout: int = 60, module: str = ':alice-facade-cli:run') -> subprocess.CompletedProcess:
    """Run the Alice Agent CLI via Gradle and return the result.

    Args:
        args: CLI arguments to pass
        timeout: Timeout in seconds
        module: Gradle task to run (default: :alice-facade-cli:run for direct CLI;
                use ':alice-bootstrap:run' for SPI-based facade selection)
    """
    cmd = build_cli_command(args, module=module)
    args_preview = " ".join(cmd[3:]) if len(cmd) > 3 else "(none)"
    print(f"  ⚙️  CLI: ./gradlew {module} --args {args_preview[:80]}")
    result = subprocess.run(
        cmd,
        cwd=PROJECT_ROOT,
        capture_output=True,
        text=True,
        timeout=timeout,
    )
    return result


def run_java_class(
    classpath: str, main_class: str, args: list[str] = None, timeout: int = 60
) -> subprocess.CompletedProcess:
    """Run a Java main class directly on the classpath."""
    cmd = [
        "java",
        "--module-path", classpath,
        "-m", main_class,
    ]
    if args:
        cmd.extend(args)
    result = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    return result


# ── External Service Checks ────────────────────────────────────────────────


def skip_if_no_gemma4() -> bool:
    """Check if Gemma4 API is reachable; return True if it should be skipped."""
    try:
        import requests
        r = requests.get(f"{GEMMA4_BASE_URL.rstrip('/v1')}/health", timeout=5)
        if r.status_code < 500:
            return False
    except Exception:
        pass
    try:
        import requests
        r = requests.post(
            f"{GEMMA4_BASE_URL}/chat/completions",
            json={"model": GEMMA4_MODEL, "messages": [{"role": "user", "content": "ping"}], "max_tokens": 5},
            timeout=10,
        )
        return r.status_code >= 500
    except Exception:
        return True
