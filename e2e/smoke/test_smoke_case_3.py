#!/usr/bin/env python3
"""
PMTEV Smoke Test — Case 3: 沙箱环境执行与TDD自省闭环测试

校验 Agent 环境执行、结果验证能力：在沙箱运行单元测试、读取报错堆栈、
自我迭代修复代码直至测试通过。

真实验证：Agent 执行后，运行 pytest 全部用例通过；
检查 parser.py 是否改为了 try/except 捕获 JSON 解析错误。

PMTEV: Execution (pytest) + Verification (read test output) + Reflection (re-fix)

Spec reference: docs/Agent 冒烟测试用例规范文档.md §Case 3

Usage:
    python -m e2e.smoke.test_smoke_case_3
"""

import subprocess
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from e2e.smoke.agent_run import restore_files, run_agent, snapshot_files
from e2e.smoke.cases import CASE_3


class TestSmokeCase3(unittest.TestCase):
    """Case 3: 执行 pytest → 捕获报错 → 修复 parser.py → 全部用例通过。"""

    maxDiff = None

    FIXTURE_DIR = Path(__file__).resolve().parent / "fixtures" / "pytest_tdd"
    PARSER = FIXTURE_DIR / "parser.py"
    TEST_FILE = FIXTURE_DIR / "test_parser.py"

    @classmethod
    def setUpClass(cls):
        cls._fixture_backup = snapshot_files([cls.PARSER, cls.TEST_FILE])
        cls.exit_code, cls.output, cls.agent_log = run_agent(
            CASE_3.problem_description, timeout=CASE_3.timeout_seconds
        )

    @classmethod
    def tearDownClass(cls):
        restore_files(cls._fixture_backup)

    def test_agent_exits_cleanly(self):
        """Agent 必须正常退出。"""
        self.assertEqual(
            self.exit_code, 0, f"Agent 退出码非 0\n---\n{self.output[-800:]}"
        )

    def test_agent_ran_the_test_suite(self):
        """调用日志应体现被测项目单元测试的执行记录（pytest / unittest）。"""
        self.assertRegex(
            self.agent_log,
            r"(?i)pytest|unittest",
            f"Agent 文件日志中未见单元测试执行记录\n---\n{self.agent_log[-800:]}",
        )

    def test_pytest_all_pass_after_fix(self):
        """Agent 执行后，pytest 全部用例通过。"""
        pytest_result = subprocess.run(
            [
                sys.executable,
                "-m",
                "pytest",
                str(self.FIXTURE_DIR),
                "-v",
                "--tb=short",
                "-p",
                "no:cacheprovider",
            ],
            capture_output=True,
            text=True,
            timeout=60,
        )
        pytest_out = pytest_result.stdout + pytest_result.stderr
        self.assertEqual(
            pytest_result.returncode,
            0,
            f"pytest 仍有失败用例:\n{pytest_out}",
        )
        # 确认两个用例都通过
        self.assertIn(
            "2 passed",
            pytest_out,
            f"未通过全部 2 个用例:\n{pytest_out}",
        )

    def test_parser_handles_invalid_json(self):
        """parser.py 必须能处理非法 JSON 输入，不再崩溃。"""
        parser_content = self.PARSER.read_text(encoding="utf-8")
        self.assertIn(
            "try",
            parser_content,
            f"parser.py 未添加 try/except 错误处理\n当前内容:\n{parser_content}",
        )
        self.assertIn(
            "except",
            parser_content,
            f"parser.py 未添加 except 捕获\n当前内容:\n{parser_content}",
        )

        # 运行时验证：非法 JSON 不崩溃且返回 {"error": "invalid"}。
        check = subprocess.run(
            [
                sys.executable,
                "-c",
                f"""import sys; sys.path.insert(0, r'{self.FIXTURE_DIR}')
from parser import parse_payload
try:
    result = parse_payload("not valid json")
    if result == {{"error": "invalid"}}:
        print("OK")
    else:
        print(f"WRONG_RESULT:{{result}}")
except Exception as e:
    print(f"CRASHED:{{type(e).__name__}}:{{e}}")""",
            ],
            capture_output=True,
            text=True,
            timeout=30,
        )
        stdout = check.stdout.strip()
        self.assertIn(
            "OK",
            stdout,
            f"parse_payload('not valid json') 未正确处理\n输出: {stdout}\nstderr: {check.stderr}",
        )


if __name__ == "__main__":
    unittest.main(verbosity=2)
