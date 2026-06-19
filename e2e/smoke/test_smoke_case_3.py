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

import os
import subprocess
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from e2e.smoke.cases import CASE_3
from e2e.smoke.config import PROJECT_MODEL, DEEPSEEK_API_KEY, WORKSPACE_DIR
from e2e.helpers import GRADLEW, PROJECT_ROOT


class TestSmokeCase3(unittest.TestCase):
    """Case 3: 执行 pytest → 捕获报错 → 修复 parser.py → 全部用例通过"""

    maxDiff = None

    WORKSPACE = WORKSPACE_DIR / "smoke__case-3"
    FIXTURE_DIR = Path(__file__).resolve().parent / "fixtures" / "pytest_tdd"

    @classmethod
    def setUpClass(cls):
        if cls.WORKSPACE.exists():
            import shutil
            shutil.rmtree(cls.WORKSPACE)
        dst = cls.WORKSPACE
        dst.mkdir(parents=True, exist_ok=True)
        import shutil
        for f in cls.FIXTURE_DIR.iterdir():
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
        self.case = CASE_3
        # Agent 的工作目录是项目根目录，path 解析相对于项目根
        # 所以 Agent 会修改原始 fixture 文件（而非 temp 副本）
        self.parser_file = Path(__file__).resolve().parent / "fixtures" / "pytest_tdd" / "parser.py"
        self.test_file = Path(__file__).resolve().parent / "fixtures" / "pytest_tdd" / "test_parser.py"

    @classmethod
    def tearDownClass(cls):
        # 恢复原始 fixture
        import subprocess
        for f in ["parser.py", "test_parser.py"]:
            fixture = cls.FIXTURE_DIR / f
            if fixture.exists():
                subprocess.run(
                    ["git", "checkout", "--", str(fixture)],
                    cwd=PROJECT_ROOT, capture_output=True)

    def test_pytest_all_pass_after_fix(self):
        """Agent 执行后，pytest 全部用例通过"""
        code, output = self._run_agent()
        self.assertEqual(code, 0, f"Agent 退出码非0\n---\n{output[-500:]}")

        # 真实验证：运行 pytest（在原始 fixture 目录上执行，因为 Agent 修改的是原始文件）
        pytest_result = subprocess.run(
            [sys.executable, "-m", "pytest", str(self.FIXTURE_DIR), "-v", "--tb=short"],
            capture_output=True, text=True, timeout=60,
        )
        pytest_out = pytest_result.stdout + pytest_result.stderr
        self.assertEqual(
            pytest_result.returncode, 0,
            f"pytest 仍有失败用例:\n{pytest_out}",
        )
        # 确认两个用例都通过
        self.assertIn(
            "2 passed",
            pytest_out,
            f"未通过全部2个用例:\n{pytest_out}",
        )

    def test_parser_handles_invalid_json(self):
        """parser.py 必须能处理非法 JSON 输入，不再崩溃"""
        code, output = self._run_agent()
        self.assertEqual(code, 0, f"Agent 退出码非0\n---\n{output[-500:]}")

        # 验证 parser.py 内容包含错误处理
        parser_content = self.parser_file.read_text(encoding="utf-8")
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

        # 验证 import 后 parse 非法 JSON 不崩溃
        check = subprocess.run(
            [sys.executable, "-c",
             f"""import sys; sys.path.insert(0, r'{self.FIXTURE_DIR}')
from parser import parse_payload
try:
    result = parse_payload("not valid json")
    if result == {{"error": "invalid"}}:
        print("OK")
    else:
        print(f"WRONG_RESULT:{{result}}")
except Exception as e:
    print(f"CRASHED:{{type(e).__name__}}:{{e}}")"""],
            capture_output=True, text=True, timeout=30,
        )
        stdout = check.stdout.strip()
        self.assertIn(
            "OK", stdout,
            f"parse_payload('not valid json') 未正确处理\n输出: {stdout}\nstderr: {check.stderr}",
        )


if __name__ == "__main__":
    unittest.main(verbosity=2)
