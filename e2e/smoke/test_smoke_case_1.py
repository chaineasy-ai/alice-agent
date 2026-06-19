#!/usr/bin/env python3
"""
PMTEV Smoke Test — Case 1: 基础工具与文件编辑闭环测试

校验 Agent 文件检索、行号定位、文件编辑工具调用能力。

真实验证：Agent 执行后，读取目标文件 math_utils.py，
检查 divide 函数是否改为抛出 ValueError。

PMTEV: Tool (file read/write) + Environment (repo access) + Verification (import & run)

Spec reference: docs/Agent 冒烟测试用例规范文档.md §Case 1

Usage:
    python -m e2e.smoke.test_smoke_case_1
"""

import os
import subprocess
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from e2e.smoke.cases import CASE_1
from e2e.smoke.config import PROJECT_MODEL, DEEPSEEK_API_KEY, WORKSPACE_DIR
from e2e.helpers import GRADLEW, PROJECT_ROOT


class TestSmokeCase1(unittest.TestCase):
    """Case 1: divide(0) 必须抛出 ValueError 而不是崩溃"""

    maxDiff = None

    WORKSPACE = WORKSPACE_DIR / "smoke__case-1"
    FIXTURE_FILE = Path(__file__).resolve().parent / "fixtures" / "math_utils" / "math_utils.py"

    @classmethod
    def setUpClass(cls):
        # Fresh copy of fixture
        if cls.WORKSPACE.exists():
            import shutil
            shutil.rmtree(cls.WORKSPACE)
        src = cls.FIXTURE_FILE.parent
        dst = cls.WORKSPACE
        dst.mkdir(parents=True, exist_ok=True)
        import shutil
        for f in src.iterdir():
            if f.is_dir():
                continue
            shutil.copy2(f, dst / f.name)

    def _run_agent(self):
        """Run Alice Agent with the case prompt."""
        prompt_flat = self.case.problem_description.replace("\n", " ").strip()
        cmd = [
            str(GRADLEW),
            ":alice-bootstrap:run",
            "--rerun-tasks",
            "--args",
            f'run "{prompt_flat}" --model {PROJECT_MODEL} --verbose',
        ]
        env = os.environ.copy()
        if DEEPSEEK_API_KEY:
            env["DEEPSEEK_API_KEY"] = DEEPSEEK_API_KEY
        result = subprocess.run(
            cmd, cwd=PROJECT_ROOT, capture_output=True, text=True,
            timeout=self.case.timeout_seconds, env=env,
        )
        return result.returncode, result.stdout + result.stderr

    def setUp(self):
        self.case = CASE_1
        # Agent 的工作目录是项目根目录，path 解析相对于项目根
        # 所以 Agent 会修改原始 fixture 文件（而非 temp 副本）
        self.original_fixture = Path(__file__).resolve().parent / "fixtures" / "math_utils" / "math_utils.py"
        self.target = self.original_fixture

    @classmethod
    def tearDownClass(cls):
        # 恢复原始 fixture
        import subprocess
        subprocess.run(
            ["git", "checkout", "--", str(cls.FIXTURE_FILE)],
            cwd=PROJECT_ROOT, capture_output=True)

    def test_divide_zero_raises_value_error(self):
        """Agent 执行后，divide(1, 0) 必须抛出 ValueError 而不是崩溃"""
        code, output = self._run_agent()
        self.assertEqual(code, 0, f"Agent 退出码非0\n---\n{output[-500:]}")

        # 真正验证：读取目标文件
        self.assertTrue(self.target.exists(), f"目标文件 {self.target} 不存在")
        content = self.target.read_text(encoding="utf-8")

        # 验证 divide 函数改为抛出 ValueError
        self.assertIn(
            "ValueError",
            content,
            f"divide 函数未抛出 ValueError\n当前内容:\n{content}",
        )

        # 验证 import 后调用 divide(1, 0) 确实抛 ValueError 而不是崩溃
        import subprocess
        check = subprocess.run(
            [sys.executable, "-c",
             f"""import sys; sys.path.insert(0, r'{self.target.parent}')
from math_utils import divide
try:
    divide(1, 0)
    print("NO_ERROR")  # 没有抛异常 = 失败
except ValueError as e:
    print(f"OK:{{e}}")
except Exception as e:
    print(f"WRONG_EXCEPTION:{{type(e).__name__}}:{{e}}")
except SystemExit:
    print("CRASHED")"""],
            capture_output=True, text=True, timeout=30,
        )
        stdout = check.stdout.strip()
        self.assertIn(
            "OK:", stdout,
            f"divide(1, 0) 没有抛出 ValueError。输出: {stdout}\nstderr: {check.stderr}",
        )
        self.assertNotEqual(
            "CRASHED", stdout,
            "divide(1, 0) 仍然导致崩溃，未被修复",
        )


if __name__ == "__main__":
    unittest.main(verbosity=2)
