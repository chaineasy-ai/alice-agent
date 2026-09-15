#!/usr/bin/env python3
"""
PMTEV Smoke Test — Case 2: 多工具协同与跨文件依赖修复测试

校验 Agent 任务规划（Plan）能力，可跨文件全局检索变量、同步修改多处关联代码。

真实验证：Agent 执行后，检查 config.py 是否将 TIMEOUT_MS 替换为 TIMEOUT_SEC，
且 client.py 中的所有引用也同步更新，单位换算正确（5000ms → 5s）。

PMTEV: Plan (cross-file refactoring) + Tool (edit multiple)

Spec reference: docs/Agent 冒烟测试用例规范文档.md §Case 2

Usage:
    python -m e2e.smoke.test_smoke_case_2
"""

import re
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from e2e.smoke.agent_run import restore_files, run_agent, snapshot_files
from e2e.smoke.cases import CASE_2


class TestSmokeCase2(unittest.TestCase):
    """Case 2: TIMEOUT_MS → TIMEOUT_SEC 跨文件重命名 + 单位换算。"""

    maxDiff = None

    FIXTURE_DIR = Path(__file__).resolve().parent / "fixtures" / "cross_file_config"
    CONFIG = FIXTURE_DIR / "config.py"
    CLIENT = FIXTURE_DIR / "client.py"

    @classmethod
    def setUpClass(cls):
        cls._fixture_backup = snapshot_files([cls.CONFIG, cls.CLIENT])
        cls.exit_code, cls.output, cls.agent_log = run_agent(
            CASE_2.problem_description, timeout=CASE_2.timeout_seconds
        )

    @classmethod
    def tearDownClass(cls):
        restore_files(cls._fixture_backup)

    def test_agent_exits_cleanly(self):
        """Agent 必须正常退出。"""
        self.assertEqual(
            self.exit_code, 0, f"Agent 退出码非 0\n---\n{self.output[-800:]}"
        )

    def test_timeout_renamed_across_files(self):
        """config.py 和 client.py 都必须将 TIMEOUT_MS 替换为 TIMEOUT_SEC。"""
        config_content = self.CONFIG.read_text(encoding="utf-8")
        self.assertIn(
            "TIMEOUT_SEC",
            config_content,
            f"config.py 未包含 TIMEOUT_SEC\n当前内容:\n{config_content}",
        )
        self.assertNotIn(
            "TIMEOUT_MS",
            config_content,
            f"config.py 仍包含旧字段 TIMEOUT_MS\n当前内容:\n{config_content}",
        )

        client_content = self.CLIENT.read_text(encoding="utf-8")
        self.assertIn(
            "TIMEOUT_SEC",
            client_content,
            f"client.py 未引用 TIMEOUT_SEC\n当前内容:\n{client_content}",
        )
        self.assertNotIn(
            "TIMEOUT_MS",
            client_content,
            f"client.py 仍引用旧字段 TIMEOUT_MS\n当前内容:\n{client_content}",
        )

    def test_unit_conversion_correct(self):
        """单位换算正确：TIMEOUT_MS = 5000 毫秒 应换算为 TIMEOUT_SEC = 5 秒。"""
        config_content = self.CONFIG.read_text(encoding="utf-8")
        # 必须精确匹配 5，不能只是"内容里出现过 5"（5000 本身也含 5）。
        self.assertTrue(
            re.search(r"^\s*TIMEOUT_SEC\s*[:=]\s*5(?:\.0+)?\s*$", config_content, re.M),
            f"config.py 中 TIMEOUT_SEC 应等于 5（5000 毫秒换算为 5 秒）\n当前内容:\n{config_content}",
        )

    def test_agent_searched_for_old_field(self):
        """调用日志应体现全局检索行为（grep / search_file）。"""
        self.assertRegex(
            self.agent_log,
            r"(?i)grep|search_file",
            f"Agent 文件日志中未见全局检索工具的执行记录\n---\n{self.agent_log[-800:]}",
        )


if __name__ == "__main__":
    unittest.main(verbosity=2)
