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

    # Skip the (incremental) pre-build
    python -m e2e.smoke.runner --no-build

    # Dry run
    python -m e2e.smoke.runner --dry-run
"""

import argparse
import sys
import unittest
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(PROJECT_ROOT))

from e2e.helpers import run_gradle
from e2e.smoke.cases import SMOKE_CASES
from e2e.smoke.config import DEEPSEEK_API_KEY, PROJECT_MODEL

CASE_FILES = {
    "smoke__case-1": "test_smoke_case_1.py",
    "smoke__case-2": "test_smoke_case_2.py",
    "smoke__case-3": "test_smoke_case_3.py",
}


def main():
    parser = argparse.ArgumentParser(
        description="PMTEV Smoke Test Runner — Alice Agent",
    )
    parser.add_argument(
        "case_id",
        nargs="?",
        help="Specific case ID to run (e.g. smoke__case-1). Omit to run all via unittest.",
    )
    parser.add_argument("--list", action="store_true", help="List available cases")
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Print what would run without executing",
    )
    parser.add_argument(
        "--no-build",
        action="store_true",
        help="Skip the pre-build (incremental installDist)",
    )
    args = parser.parse_args()

    if args.list:
        print("Available PMTEV smoke cases:")
        for case in SMOKE_CASES:
            print(f"  {case.instance_id}: {case.problem_description[:60]}...")
        sys.exit(0)

    test_dir = Path(__file__).parent
    pattern = "test_smoke_case_*.py"

    if args.case_id:
        if args.case_id not in CASE_FILES:
            print(f"Unknown case: {args.case_id}")
            sys.exit(1)
        pattern = CASE_FILES[args.case_id]

    if args.dry_run:
        print(f"[DRY RUN] Would run: python -m unittest discover -s {test_dir} -p {pattern}")
        sys.exit(0)

    print(f"\n{'=' * 64}")
    print(f"  PMTEV Smoke Test Runner")
    print(f"  Model:   {PROJECT_MODEL}")
    print(f"  Pattern: {pattern}")
    print(f"{'=' * 64}")

    if not DEEPSEEK_API_KEY:
        print("  ⚠️  DEEPSEEK_API_KEY 未设置，Agent 调用大概率会失败。")

    # 先把 Agent 构建好：否则第一个用例要把编译时间算进自己的超时预算。
    # installDist 是增量的，源码没变时只需几秒。
    if not args.no_build:
        print("\n🔨 Pre-build: ./gradlew :alice-bootstrap:installDist")
        build = run_gradle(":alice-bootstrap:installDist", timeout=600)
        if build.returncode != 0:
            print("❌ Pre-build failed, aborting.")
            sys.exit(1)

    loader = unittest.TestLoader()
    suite = loader.discover(str(test_dir), pattern=pattern)
    runner = unittest.TextTestRunner(verbosity=2)
    result = runner.run(suite)

    print(f"\n{'=' * 64}")
    print(
        f"  Smoke Test Summary\n"
        f"  Ran: {result.testsRun}  "
        f"Failures: {len(result.failures)}  Errors: {len(result.errors)}"
    )
    print(f"{'=' * 64}")
    sys.exit(0 if result.wasSuccessful() else 1)


if __name__ == "__main__":
    main()
