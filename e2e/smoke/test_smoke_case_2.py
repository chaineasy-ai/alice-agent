#!/usr/bin/env python3
"""
PMTEV Smoke Test — Case 2: 多工具协同与跨文件依赖修复测试

校验 Agent 任务规划（Plan）能力，可跨文件全局检索变量、同步修改多处关联代码。

真实验证：Agent 执行后，检查 config.py 是否将 TIMEOUT_MS 替换为 TIMEOUT_SEC，
且 client.py 中的所有引用也同步更新，单位换算正确。

PMTEV: Plan (cross-file refactoring) + Tool (edit multiple)

Spec reference: docs/Agent 冒烟测试用例规范文档.md §Case 2

Usage:
    python -m e2e.smoke.test_smoke_case_2
"""

import os
import subprocess
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from e2e.smoke.cases import CASE_2
from e2e.smoke.config import PROJECT_MODEL, DEEPSEEK_API_KEY, WORKSPACE_DIR
from e2e.helpers import GRADLEW, PROJECT_ROOT


class TestSmokeCase2(unittest.TestCase):
    """Case 2: TIMEOUT_MS → TIMEOUT_SEC 跨文件重命名 + 单位换算"""

    maxDiff = None

    WORKSPACE = WORKSPACE_DIR / "smoke__case-2"
    FIXTURE_DIR = Path(__file__).resolve().parent / "fixtures" / "cross_file_config"

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
            "--no-build-cache",
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
        self.case = CASE_2

    def test_timeout_renamed_across_files(self):
        """config.py 和 client.py 都必须将 TIMEOUT_MS 替换为 TIMEOUT_SEC"""
        code, output = self._run_agent()
        self.assertEqual(code, 0, f"Agent 退出码非0\n---\n{output[-500:]}")

        # 验证 config.py
        config_file = self.WORKSPACE / "config.py"
        self.assertTrue(config_file.exists(), "config.py 不存在")
        config_content = config_file.read_text(encoding="utf-8")
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

        # 验证 client.py
        client_file = self.WORKSPACE / "client.py"
        self.assertTrue(client_file.exists(), "client.py 不存在")
        client_content = client_file.read_text(encoding="utf-8")
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
        """单位换算正确：5秒 = 5000毫秒"""
        code, output = self._run_agent()
        self.assertEqual(code, 0, f"Agent 退出码非0\n---\n{output[-500:]}")

        config_content = (self.WORKSPACE / "config.py").read_text(encoding="utf-8")

        # 原 TIMEOUT_MS = 5000 → 换算后 TIMEOUT_SEC = 5
        self.assertIn(
            "5", config_content,
            f"未找到正确的单位换算: TIMEOUT_SEC 应=5\n当前内容:\n{config_content}",
        )


if __name__ == "__main__":
    unittest.main(verbosity=2)
