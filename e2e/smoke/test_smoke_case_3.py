#!/usr/bin/env python3
"""
PMTEV Smoke Test — Case 3: 沙箱环境执行与TDD自省闭环测试

校验 Agent 环境执行、结果验证能力：在沙箱运行单元测试、读取报错堆栈、
自我迭代修复代码直至测试通过。

PMTEV: Execution (pytest) + Verification (read test output) + Reflection (re-fix)

Spec reference: docs/Agent 冒烟测试用例规范文档.md §Case 3

Usage:
    python -m e2e.smoke.test_smoke_case_3
    python -m e2e.smoke.runner smoke__case-3
"""

import os
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from e2e.smoke.cases import CASE_3
from e2e.smoke.runner import prepare_workspace, run_alice_agent, verify_case


class TestSmokeCase3(unittest.TestCase):
    """Case 3: 沙箱环境执行与TDD自省闭环测试 — pytest 测试→修复→再验证"""

    maxDiff = None

    def setUp(self):
        self.case = CASE_3
        self.workspace = prepare_workspace(self.case)

    def test_tdd_loop_runs_pytest_and_self_corrects(self):
        """校验 Agent 执行 pytest 捕获报错，自省迭代修改代码直至测试通过"""
        output = run_alice_agent(
            target_dir=self.workspace,
            prompt=self.case.problem_description,
            timeout=self.case.timeout_seconds,
        )
        failures = verify_case(self.case, output)
        self.assertEqual(
            [], failures,
            f"Case 3 assertions failed: {failures}\n---\nOutput tail:\n{output[-500:]}"
        )


if __name__ == "__main__":
    unittest.main(verbosity=2)
