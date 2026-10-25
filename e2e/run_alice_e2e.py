#!/usr/bin/env python3
"""
Convenience runner for the Alice Agent E2E test suite.

Discovers and runs:
  - e2e/alice_agent_e2e.py  — primary E2E scenarios (S-01, S-02, S-03)
  - e2e/test_dispatch.py    — AgentCommand dispatch coverage (21 subtypes)
  - e2e/smoke/              — PMTEV smoke test framework (3 standard cases)
  - e2e/gemma4/             — Gemma4 integration tests

Usage:
    # Build then test
    python run_alice_e2e.py --build

    # Test only (assumes already built)
    python run_alice_e2e.py

    # Run a specific test class
    python run_alice_e2e.py TestAliceCliHelp

    # Run with verbose output
    python run_alice_e2e.py -v

Environment variables:
    GEMMA4_BASE_URL  (default: http://192.168.1.14:10303/v1)
    GEMMA4_MODEL     (default: gemma-4)
    GEMMA4_TIMEOUT   (default: 180)

Exit codes:
    0  全绿（且工作树未被污染）
    1  真红（有用例失败/工作树被污染）
    2  未判定——全量跑却在 <15s 内整片失败，多半是仪表坏了（JAVA_HOME/构建产物/daemon）
"""

import os
import sys
import time
import unittest

#: 「快得可疑」判据（#415 · atlas 口径：窄，不做「快就失败」）
#: 只有 **全量跑** + **有失败** + **耗时 < 该秒数** 才判「未判定」(exit 2)。
#: 单跑某个类/方法（`-k` 式选测）本来就快，不适用。
SUSPICIOUS_FAST_FAIL_SECONDS = 15.0
EXIT_UNDETERMINED = 2

if __name__ == "__main__":
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    from helpers import git_status_porcelain, worktree_guard_enabled

    # 是否只跑了部分用例（类/方法名选择器）——「快」在选测下是正常的，护栏不适用
    selectors = [a for a in sys.argv[1:] if not a.startswith("-")]

    # Default verbosity
    if "-v" not in sys.argv:
        sys.argv.append("-v")

    e2e_dir = os.path.dirname(os.path.abspath(__file__))

    # ── Working-tree isolation gate (#417) ─────────────────────────────
    # E2E runs may write into the repo (the CLI resolves relative paths to the
    # project root).  A dirty tree silently breaks `./gradlew check`, so refuse
    # to start from one and verify the suite leaves the tree as it found it.
    allow_dirty = not worktree_guard_enabled()
    dirty_before = git_status_porcelain()
    if dirty_before and not allow_dirty:
        print("\n  ❌ Working tree is not clean -- refusing to run E2E (#417).")
        print("     Uncommitted entries:")
        for line in dirty_before:
            print(f"       {line}")
        print("     Commit/stash them, or set ALICE_E2E_ALLOW_DIRTY=1 to bypass.\n")
        sys.exit(3)
    baseline = set(dirty_before)

    # Discover tests from multiple locations
    loader = unittest.TestLoader()
    suite = unittest.TestSuite()

    # 1. Primary E2E scenarios (alice_agent_e2e.py pattern)
    suite.addTests(loader.discover(e2e_dir, pattern="alice_agent_e2e*.py"))

    # 2. Dispatch coverage (test_dispatch.py)
    suite.addTests(loader.discover(e2e_dir, pattern="test_dispatch*.py"))

    # 2b. E2E harness regression guards (test_e2e_guards.py -- #416/#417)
    suite.addTests(loader.discover(e2e_dir, pattern="test_e2e_guards*.py"))

    # 3. PMTEV smoke tests (e2e/smoke/)
    smoke_dir = os.path.join(e2e_dir, "smoke")
    if os.path.isdir(smoke_dir):
        suite.addTests(loader.discover(smoke_dir, pattern="test_smoke_case_*.py"))

    # 4. Gemma4 integration tests (e2e/gemma4/)
    gemma4_dir = os.path.join(e2e_dir, "gemma4")
    if os.path.isdir(gemma4_dir):
        suite.addTests(loader.discover(gemma4_dir, pattern="*_e2e_test.py"))

    runner = unittest.TextTestRunner(verbosity=2)
    started = time.monotonic()
    result = runner.run(suite)
    elapsed = time.monotonic() - started

    # ── Post-run isolation check (#417) ────────────────────────────────
    new_dirty = [line for line in git_status_porcelain() if line not in baseline]
    tree_polluted = bool(new_dirty) and not allow_dirty
    if new_dirty:
        print("\n  ❌ E2E polluted the working tree (#417) -- new git status entries:")
        for line in new_dirty:
            print(f"       {line}")
    elif not allow_dirty:
        print("\n  ✅ Working tree clean before and after E2E (#417)")

    print()
    print("=" * 64)
    print(f"  Tests run: {result.testsRun}")
    print(f"  Passed:    {result.testsRun - len(result.failures) - len(result.errors)}")
    print(f"  Failures:  {len(result.failures)}")
    print(f"  Errors:    {len(result.errors)}")
    print(f"  Skipped:   {len(result.skipped)}")
    print(f"  Elapsed:   {elapsed:.1f}s")
    print("=" * 64)

    # ── 「快得可疑」护栏（#415）────────────────────────────────────────
    # 仪表坏了（JAVA_HOME 没设 / gradle daemon 挂 / 依赖缺失）时，全量套件会在
    # 几秒内整片全红——那不是产品红，是**没判定**。atlas 踩过：7 failed in 2.14s。
    # 判据要窄：仅「全量 + 有失败 + <15s」→ exit 2（未判定），其余照旧。
    failures_total = len(result.failures) + len(result.errors)
    if failures_total and elapsed < SUSPICIOUS_FAST_FAIL_SECONDS and not selectors:
        print()
        print(f"  ⚠️  未判定（exit {EXIT_UNDETERMINED}）：{failures_total} 个失败只用了 {elapsed:.1f}s"
              f"（< {SUSPICIOUS_FAST_FAIL_SECONDS:.0f}s）。")
        print("     全量套件不可能这么快——先查**仪表**，别据此判交付红：")
        print("       · JAVA_HOME 是否设了（./gradlew --version 能过吗）")
        print("       · ./alice-bootstrap/build/install 是否存在（--build 重建）")
        print("       · Gradle daemon 是否异常（./gradlew --stop 后重试）")
        sys.exit(EXIT_UNDETERMINED)

    sys.exit(0 if result.wasSuccessful() and not tree_polluted else 1)
