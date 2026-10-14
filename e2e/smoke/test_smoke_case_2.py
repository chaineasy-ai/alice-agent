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

import io
import re
import sys
import tokenize
import unittest
from pathlib import Path

def _code_without_comments(source: str) -> str:
    """剥离 Python 注释，返回仅含代码/字符串的文本（#521 P-4）。

    用途：判定"旧字段是否仍被**代码**引用" —— 迁移说明写在注释里（如
    ``# migrated from legacy TIMEOUT_MS``）不应算失败。用 tokenizer 剥离
    ``COMMENT`` token，避免"判原始字符串而非代码引用"的假红。
    """
    try:
        tokens = tokenize.generate_tokens(io.StringIO(source).readline)
        kept = [t for t in tokens if t.type != tokenize.COMMENT]
        return tokenize.untokenize(kept)
    except (tokenize.TokenError, IndentationError):
        return source  # 语法异常时退回原文，保守判定


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
        """config.py 和 client.py 的**代码**都不再引用旧字段 TIMEOUT_MS（#521 P-4）。

        判"代码引用"而非"原始字符串"：注释/文档里的迁移说明（如
        ``# migrated from legacy TIMEOUT_MS``）不算失败。
        """
        config_content = self.CONFIG.read_text(encoding="utf-8")
        self.assertIn(
            "TIMEOUT_SEC",
            config_content,
            f"config.py 未包含 TIMEOUT_SEC\n当前内容:\n{config_content}",
        )
        config_code = _code_without_comments(config_content)
        self.assertNotIn(
            "TIMEOUT_MS",
            config_code,
            f"config.py 代码仍引用旧字段 TIMEOUT_MS\n当前内容:\n{config_content}",
        )

        client_content = self.CLIENT.read_text(encoding="utf-8")
        self.assertIn(
            "TIMEOUT_SEC",
            client_content,
            f"client.py 未引用 TIMEOUT_SEC\n当前内容:\n{client_content}",
        )
        client_code = _code_without_comments(client_content)
        self.assertNotIn(
            "TIMEOUT_MS",
            client_code,
            f"client.py 代码仍引用旧字段 TIMEOUT_MS\n当前内容:\n{client_content}",
        )

    def test_unit_conversion_correct(self):
        """单位换算正确：TIMEOUT_MS = 5000 毫秒 应换算为 TIMEOUT_SEC = 5 秒。"""
        config_content = self.CONFIG.read_text(encoding="utf-8")
        # 必须精确匹配 5，不能只是"内容里出现过 5"（5000 本身也含 5）。
        self.assertTrue(
            re.search(r"^\s*TIMEOUT_SEC\s*[:=]\s*5(?:\.0+)?\s*$", config_content, re.M),
            f"config.py 中 TIMEOUT_SEC 应等于 5（5000 毫秒换算为 5 秒）\n当前内容:\n{config_content}",
        )

    def test_old_field_removed_or_retrieval_evidence(self):
        """P-4（#521）：判**结果**而非实现 —— outcome-or-evidence。

        通过条件（任一即可）：
        1. **outcome**：目标文件里旧字段 ``TIMEOUT_MS`` 已消失（手段不限，
           Agent 用 ``read_file`` 直达同样合法）；
        2. **evidence**：Agent 文件日志出现检索/读取类工具调用（规范 Case2 断言 #2
           的"全局检索证据面"，**降为证据面之一，非唯一通过条件**）。

        背景：原断言 ``(?i)grep|search_file`` 只认"用了哪个工具"，Agent 用
        ``read_file`` 达成同一目标会被判红（实现耦合的假失败）。
        """
        config_content = self.CONFIG.read_text(encoding="utf-8")
        client_content = self.CLIENT.read_text(encoding="utf-8")
        outcome = ("TIMEOUT_MS" not in config_content) and ("TIMEOUT_MS" not in client_content)
        evidence = re.search(
            r"(?i)grep|search_file|read_file|list_dir", self.agent_log
        ) is not None
        self.assertTrue(
            outcome or evidence,
            f"既无改名结果、也无检索/读取证据（outcome={outcome}, evidence={evidence}）\n"
            f"--- config.py ---\n{config_content}\n--- client.py ---\n{client_content}\n"
            f"--- log tail ---\n{self.agent_log[-500:]}",
        )


if __name__ == "__main__":
    unittest.main(verbosity=2)
