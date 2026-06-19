#!/usr/bin/env python3
"""
PMTEV Smoke Test — Case 1: 基础工具与文件编辑闭环测试

校验 Agent 文件检索、行号定位、文件编辑工具调用能力，输出合规 Git 差异补丁。

PMTEV: Tool (file read/write) + Environment (repo access) + Verification (git diff)

Spec reference: docs/Agent 冒烟测试用例规范文档.md §Case 1

Usage:
    python -m e2e.smoke.test_smoke_case_1
    python -m e2e.smoke.runner smoke__case-1
"""

import os
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from e2e.smoke.cases import CASE_1
from e2e.smoke.runner import prepare_workspace, run_alice_agent, verify_case


class TestSmokeCase1(unittest.TestCase):
    """Case 1: 基础工具与文件编辑闭环测试 — divide 函数修复"""

    maxDiff = None

    def setUp(self):
        self.case = CASE_1
        self.workspace = prepare_workspace(self.case)

    def test_divide_fix_produces_git_diff(self):
        """校验补丁输出为标准 Git Diff 格式，包含 raise ValueError"""
        output = run_alice_agent(
            target_dir=self.workspace,
            prompt=self.case.problem_description,
            timeout=self.case.timeout_seconds,
        )
        failures = verify_case(self.case, output)
        self.assertEqual(
            [], failures,
            f"Case 1 assertions failed: {failures}\n---\nOutput tail:\n{output[-500:]}"
        )


if __name__ == "__main__":
    unittest.main(verbosity=2)
