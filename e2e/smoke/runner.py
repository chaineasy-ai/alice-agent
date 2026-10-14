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
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(PROJECT_ROOT))

from e2e.helpers import git_status_porcelain, run_gradle, worktree_guard_enabled
from e2e.smoke.cases import SMOKE_CASES
from e2e.smoke.config import DEEPSEEK_API_KEY, PROJECT_MODEL

CASE_FILES = {
    "smoke__case-1": "test_smoke_case_1.py",
    "smoke__case-2": "test_smoke_case_2.py",
    "smoke__case-3": "test_smoke_case_3.py",
}


def _reexec_isolated(rest_args: list[str]) -> None:
    """P-3 (#521)：在独立 git worktree + 私有 HOME 内**构建并**重跑本 runner。

    - worktree 自带 ``build/`` ⇒ 每个并发 run 的 Gradle 互不共享构建输出，不互踩；
    - 分发包在 **worktree 内** ``installDist`` 构建，保证「代码 == 被测二进制」（不会
      用主仓旧 dist 假绿）；
    - Gradle 依赖缓存（``GRADLE_USER_HOME``）仍复用真实 HOME，避免重复下载；
    - fixtures / ``~/.alice`` logs / WAL 全在 worktree+私有 HOME，主仓零污染；
    - 结束后回收 worktree 与临时 HOME。

    注意：worktree 取 **HEAD**（不含未提交改动）；主仓脏树默认拒绝（与 #417 一致）。
    """
    allow_dirty = not worktree_guard_enabled()
    dirty = git_status_porcelain()
    if dirty and not allow_dirty:
        print("\n  ❌ Working tree is not clean -- isolate 基于 HEAD，拒绝运行 (#417).")
        for line in dirty:
            print(f"       {line}")
        print("     Commit/stash them, or set ALICE_E2E_ALLOW_DIRTY=1 to bypass.\n")
        sys.exit(3)

    # Gradle 依赖缓存复用真实 HOME；被测运行的 HOME 另设私有目录。
    real_gradle_home = os.environ.get("GRADLE_USER_HOME") or str(Path.home() / ".gradle")
    # Python 用户级包（pip --user，如 pytest）位于真实 ~/.local；私有 HOME 会遮蔽它。
    # 固定 PYTHONUSERBASE 指向真实 base，使 `python -m pytest` 在隔离态仍可见。
    real_userbase = os.environ.get("PYTHONUSERBASE") or str(Path.home() / ".local")

    tmp = Path(tempfile.mkdtemp(prefix="alice-smoke-iso-"))
    wt = tmp / "wt"
    home = tmp / "home"
    home.mkdir(parents=True, exist_ok=True)

    add = subprocess.run(
        ["git", "worktree", "add", "--detach", str(wt), "HEAD"],
        cwd=str(PROJECT_ROOT), capture_output=True, text=True,
    )
    if add.returncode != 0:
        print(f"❌ git worktree add failed: {add.stderr.strip()}")
        shutil.rmtree(tmp, ignore_errors=True)
        sys.exit(1)

    wt_dist = wt / "alice-bootstrap" / "build" / "install" / "alice" / "bin" / "alice"
    print(f"\n🧪 Isolated run: worktree={wt}\n"
          f"                home={home}\n"
          f"                bin={wt_dist}", flush=True)

    try:
        # 在隔离 worktree 内构建（自带 build 目录，Gradle 不与其他 run 共享输出）。
        build_env = os.environ.copy()
        build_env["HOME"] = str(home)
        build_env["GRADLE_USER_HOME"] = real_gradle_home
        print("\n🔨 Pre-build (isolated worktree): ./gradlew :alice-bootstrap:installDist", flush=True)
        try:
            build = subprocess.run(
                ["./gradlew", ":alice-bootstrap:installDist", "--console=plain"],
                cwd=str(wt), env=build_env, timeout=1800,
            )
        except subprocess.TimeoutExpired:
            print("❌ Pre-build (isolate) timed out, aborting.")
            sys.exit(1)
        if build.returncode != 0:
            print("❌ Pre-build (isolate) failed, aborting.")
            sys.exit(1)

        env = os.environ.copy()
        env["ALICE_SMOKE_ISOLATED"] = "1"
        env["ALICE_SMOKE_BIN"] = str(wt_dist)
        env["HOME"] = str(home)
        env["GRADLE_USER_HOME"] = real_gradle_home
        env["PYTHONUSERBASE"] = real_userbase
        env["ALICE_OPTS"] = (env.get("ALICE_OPTS", "") + f' "-Duser.home={home}"').strip()
        env["PYTHONPATH"] = str(wt) + os.pathsep + env.get("PYTHONPATH", "")
        # 临时 worktree：运行后的 fixtures 改动无意义，禁用 #417 污染门（准备前 --detach 干净）
        env["ALICE_E2E_ALLOW_DIRTY"] = "1"
        sys.stdout.flush()
        cmd = [
            sys.executable,
            "-m",
            "e2e.smoke.runner",
            "--no-isolate",
            "--no-build",
            *rest_args,
        ]
        rc = subprocess.run(cmd, cwd=str(wt), env=env).returncode
    finally:
        subprocess.run(
            ["git", "worktree", "remove", "--force", str(wt)],
            cwd=str(PROJECT_ROOT), capture_output=True, text=True,
        )
        shutil.rmtree(tmp, ignore_errors=True)
    sys.exit(rc)


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
    parser.add_argument(
        "--isolate",
        action=argparse.BooleanOptionalAction,
        default=True,
        help="P-3 (#521): run in an isolated git worktree + private HOME via the prebuilt "
        "distribution (default: on). Use --no-isolate to run in-place.",
    )
    parser.add_argument(
        "--graph-kernel",
        choices=["on", "off"],
        default=None,
        help="P-2 (#521): run the smoke suite on the graph kernel (on) or the legacy "
        "path (off). Omit to use the CLI default (on).",
    )
    args = parser.parse_args()

    if args.graph_kernel:
        os.environ["ALICE_SMOKE_GRAPH_KERNEL"] = args.graph_kernel

    # ── P-3 (#521): worktree/HOME isolation (default on) ───────────────
    # 在独立 git worktree + 私有 HOME 内、用预构建分发包跑（不触发 Gradle），
    # 消除共享工作树并发 Gradle 互踩（#515/#521-3）。
    if (
        args.isolate
        and not args.list
        and not args.dry_run
        and os.environ.get("ALICE_SMOKE_ISOLATED") != "1"
    ):
        rest = [a for a in sys.argv[1:] if a not in ("--isolate", "--no-isolate")]
        _reexec_isolated(rest)
        return  # unreachable (sys.exit in helper)

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
    print(f"  Kernel:  {args.graph_kernel or 'default (on)'}")
    print(f"{'=' * 64}")

    # ── Working-tree isolation gate (#417) ─────────────────────────────
    allow_dirty = not worktree_guard_enabled()
    dirty_before = git_status_porcelain()
    if dirty_before and not allow_dirty:
        print("\n  ❌ Working tree is not clean -- refusing to run smoke tests (#417).")
        for line in dirty_before:
            print(f"       {line}")
        print("     Commit/stash them, or set ALICE_E2E_ALLOW_DIRTY=1 to bypass.\n")
        sys.exit(3)
    baseline = set(dirty_before)

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

    new_dirty = [line for line in git_status_porcelain() if line not in baseline]
    tree_polluted = bool(new_dirty) and not allow_dirty
    if new_dirty:
        print("\n  ❌ Smoke run polluted the working tree (#417):")
        for line in new_dirty:
            print(f"       {line}")

    print(f"\n{'=' * 64}")
    print(
        f"  Smoke Test Summary\n"
        f"  Ran: {result.testsRun}  "
        f"Failures: {len(result.failures)}  Errors: {len(result.errors)}"
    )
    print(f"{'=' * 64}")
    sys.exit(0 if result.wasSuccessful() and not tree_polluted else 1)


if __name__ == "__main__":
    main()
