#!/usr/bin/env python3
"""
PMTEV Smoke Test Runner — batch driver for all 3 standard smoke cases.

Reference implementation per docs/Agent 冒烟测试用例规范文档.md §本地冒烟测试执行集成方案.

Usage:
    # Run all 3 cases via unittest discovery
    python -m e2e.smoke.runner

    # Run a specific case by ID
    python -m e2e.smoke.runner smoke__case-1

    # List available cases
    python -m e2e.smoke.runner --list

    # Dry run
    python -m e2e.smoke.runner --dry-run
"""

import argparse
import os
import subprocess
import sys
import time
import unittest
from pathlib import Path
from typing import Optional

PROJECT_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(PROJECT_ROOT))

from e2e.smoke.config import SmokeCase, FIXTURES_DIR, WORKSPACE_DIR, PROJECT_MODEL, DEEPSEEK_API_KEY
from e2e.smoke.cases import SMOKE_CASES, SMOKE_CASES_BY_ID
from e2e.helpers import GRADLEW, run_gradle


# ── Fixture Repository Setup ───────────────────────────────────────────────


def ensure_fixture(case: SmokeCase) -> Path:
    """Ensure a fixture repository exists for the given smoke case."""
    repo = case.repo_path
    repo.mkdir(parents=True, exist_ok=True)
    return repo


def prepare_workspace(case: SmokeCase) -> Path:
    """Prepare a clean workspace copy of the fixture repo."""
    ws = WORKSPACE_DIR / case.instance_id
    if ws.exists():
        import shutil
        shutil.rmtree(ws)
    repo = ensure_fixture(case) if not case.repo_path.exists() else case.repo_path
    if case.repo_path != ws:
        import shutil
        shutil.copytree(str(repo), str(ws), dirs_exist_ok=True)
    return ws


# ── Alice Agent Invocation ─────────────────────────────────────────────────


def run_alice_agent(
    target_dir: Path,
    prompt: str,
    model: Optional[str] = None,
    timeout: int = 300,
) -> str:
    """Invoke Alice Agent on a target repo with a problem description.

    Returns stdout+stderr from the agent run.
    """
    model = model or PROJECT_MODEL
    prompt_flat = prompt.replace("\n", " ").replace("\r", " ").strip()
    cmd = [
        str(GRADLEW),
        ":alice-bootstrap:run",
        "--no-build-cache",
        "--args",
        f'run "{prompt_flat}" --model {model} --verbose',
    ]
    print(f"\n  🚀 Agent invocation: {target_dir.name}")
    print(f"     model={model}  prompt={prompt_flat[:80]}...")

    env = os.environ.copy()
    if DEEPSEEK_API_KEY:
        env["DEEPSEEK_API_KEY"] = DEEPSEEK_API_KEY

    result = subprocess.run(
        cmd,
        cwd=PROJECT_ROOT,
        capture_output=True,
        text=True,
        timeout=timeout,
        env=env,
    )
    output = result.stdout + result.stderr

    if result.returncode == 0:
        print(f"  ✅ Agent completed (exit={result.returncode})")
    else:
        print(f"  ⚠️  Agent exit={result.returncode}")

    return output


# ── CLI Driver ─────────────────────────────────────────────────────────────


def main():
    parser = argparse.ArgumentParser(
        description="PMTEV Smoke Test Runner — Alice Agent",
    )
    parser.add_argument(
        "case_id", nargs="?",
        help="Specific case ID to run (e.g. smoke__case-1). Omit to run all via unittest.",
    )
    parser.add_argument("--list", action="store_true", help="List available cases")
    parser.add_argument("--dry-run", action="store_true", help="Print what would run without executing")
    parser.add_argument("--build", action="store_true", help="Rebuild Alice Agent before running")
    args = parser.parse_args()

    if args.list:
        print("Available PMTEV smoke cases:")
        for case in SMOKE_CASES:
            print(f"  {case.instance_id}: {case.problem_description[:60]}...")
        sys.exit(0)

    if args.build:
        print("🔨 Building Alice Agent distribution...")
        run_gradle(":alice-bootstrap:installDist", timeout=300)

    test_dir = Path(__file__).parent
    pattern = "test_smoke_case_*.py"

    if args.case_id:
        file_map = {
            "smoke__case-1": "test_smoke_case_1.py",
            "smoke__case-2": "test_smoke_case_2.py",
            "smoke__case-3": "test_smoke_case_3.py",
        }
        if args.case_id not in file_map:
            print(f"Unknown case: {args.case_id}")
            sys.exit(1)
        pattern = file_map[args.case_id]

    if args.dry_run:
        print(f"[DRY RUN] Would run: python -m unittest discover -s {test_dir} -p {pattern}")
        sys.exit(0)

    print(f"\n{'=' * 64}")
    print(f"  PMTEV Smoke Test Runner")
    print(f"  Pattern: {pattern}")
    print(f"{'=' * 64}")

    loader = unittest.TestLoader()
    suite = loader.discover(str(test_dir), pattern=pattern)
    runner = unittest.TextTestRunner(verbosity=2)
    result = runner.run(suite)

    print(f"\n{'=' * 64}")
    print(f"  Smoke Test Summary")
    print(f"  Ran: {result.testsRun}  Failures: {len(result.failures)}  Errors: {len(result.errors)}")
    print(f"{'=' * 64}")
    sys.exit(0 if result.wasSuccessful() else 1)


if __name__ == "__main__":
    main()
