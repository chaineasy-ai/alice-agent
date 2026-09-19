#!/usr/bin/env python3
"""
PMTEV Smoke Test — Case 1: 基础工具与文件编辑闭环测试

校验 Agent 文件检索、行号定位、文件编辑工具调用能力。

真实验证：Agent 执行后，读取目标文件 math_utils.py，
检查 divide 函数是否改为抛出 ValueError，并真实 import 验证运行时行为。

PMTEV: Tool (file read/write) + Environment (repo access) + Verification (import & run)

Spec reference: docs/Agent 冒烟测试用例规范文档.md §Case 1

Usage:
    python -m e2e.smoke.test_smoke_case_1
"""

import subprocess
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from e2e.smoke.agent_run import restore_files, run_agent, snapshot_files
from e2e.smoke.cases import CASE_1


class TestSmokeCase1(unittest.TestCase):
    """Case 1: divide(0) 必须抛出 ValueError 而不是崩溃。"""

    maxDiff = None

    FIXTURE_DIR = Path(__file__).resolve().parent / "fixtures" / "math_utils"
    TARGET = FIXTURE_DIR / "math_utils.py"

    @classmethod
    def setUpClass(cls):
        # 用例开始前快照 fixture，用例结束后无条件恢复。
        cls._fixture_backup = snapshot_files([cls.TARGET])
        # Agent 的工作目录是项目根目录，prompt 里的路径相对于项目根，
        # 因此 Agent 修改的就是这个原始 fixture；每个用例只调用一次 Agent。
        cls.exit_code, cls.output, cls.agent_log = run_agent(
            CASE_1.problem_description, timeout=CASE_1.timeout_seconds
        )

    @classmethod
    def tearDownClass(cls):
        restore_files(cls._fixture_backup)

    def test_agent_exits_cleanly(self):
        """Agent 必须正常退出（否则后面的断言都是在验证一个失败的任务）。"""
        self.assertEqual(
            self.exit_code, 0, f"Agent 退出码非 0\n---\n{self.output[-800:]}"
        )

    def test_divide_zero_raises_value_error(self):
        """Agent 执行后，divide(1, 0) 必须抛出 ValueError 而不是崩溃。"""
        self.assertTrue(self.TARGET.exists(), f"目标文件 {self.TARGET} 不存在")
        content = self.TARGET.read_text(encoding="utf-8")

        self.assertIn(
            "raise ValueError",
            content,
            f"divide 函数未抛出 ValueError\n当前内容:\n{content}",
        )

        # 运行时验证：import 后调用 divide(1, 0) 确实抛 ValueError 而不是崩溃。
        check = subprocess.run(
            [
                sys.executable,
                "-c",
                f"""import sys; sys.path.insert(0, r'{self.TARGET.parent}')
from math_utils import divide
try:
    divide(1, 0)
    print("NO_ERROR")  # 没有抛异常 = 失败
except ValueError as e:
    print(f"OK:{{e}}")
except Exception as e:
    print(f"WRONG_EXCEPTION:{{type(e).__name__}}:{{e}}")""",
            ],
            capture_output=True,
            text=True,
            timeout=30,
        )
        stdout = check.stdout.strip()
        self.assertIn(
            "OK:",
            stdout,
            f"divide(1, 0) 没有抛出 ValueError。输出: {stdout}\nstderr: {check.stderr}",
        )
        self.assertNotIn(
            "CRASHED",
            stdout,
            "divide(1, 0) 仍然导致崩溃，未被修复",
        )


if __name__ == "__main__":
    unittest.main(verbosity=2)
