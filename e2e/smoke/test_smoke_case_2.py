#!/usr/bin/env python3
"""
PMTEV Smoke Test — Case 2: 多工具协同与跨文件依赖修复测试

校验 Agent 任务规划（Plan）能力，可跨文件全局检索变量、同步修改多处关联代码。

PMTEV: Plan (cross-file refactoring) + Model (grep/search) + Tool (edit multiple)

Spec reference: docs/Agent 冒烟测试用例规范文档.md §Case 2

Usage:
    python -m e2e.smoke.test_smoke_case_2
    python -m e2e.smoke.runner smoke__case-2
"""

import os
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from e2e.smoke.cases import CASE_2
from e2e.smoke.runner import prepare_workspace, run_alice_agent, verify_case


class TestSmokeCase2(unittest.TestCase):
    """Case 2: 多工具协同与跨文件依赖修复测试 — TIMEOUT_MS → TIMEOUT_SEC"""

    maxDiff = None

    def setUp(self):
        self.case = CASE_2
        self.workspace = prepare_workspace(self.case)

    def test_cross_file_refactor_produces_multi_file_patch(self):
        """校验补丁同时包含 config.py 和 client.py 修改，且有全局检索日志"""
        output = run_alice_agent(
            target_dir=self.workspace,
            prompt=self.case.problem_description,
            timeout=self.case.timeout_seconds,
        )
        failures = verify_case(self.case, output)
        self.assertEqual(
            [], failures,
            f"Case 2 assertions failed: {failures}\n---\nOutput tail:\n{output[-500:]}"
        )


if __name__ == "__main__":
    unittest.main(verbosity=2)
