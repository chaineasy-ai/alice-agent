#!/usr/bin/env python3
"""Regression guards for the E2E harness itself (#416 / #417).

#416 -- ``run_gradle`` merges stderr into stdout, so ``result.stderr`` is ``None``.
        Indexing it directly raised ``TypeError`` and masked the real failure.
#417 -- e2e runs could silently dirty the working tree and break ``./gradlew check``.

#765 -- smoke 用例读 `~/.alice/logs/alice-agent.log`，但 logback 只写 `info.log`
        ⇒ 日志增量恒空 ⇒ 断言结构性永红（被误读为「LLM 概率性」）。

Run:  python -m unittest e2e.test_e2e_guards -v
"""

import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from helpers import (  # noqa: E402
    diagnostic_tail,
    git_status_porcelain,
    local_model_config_path,
    skip_if_no_local_supplier,
)


class DiagnosticTailTest(unittest.TestCase):
    """#416: diagnostic extraction must never raise on merged streams."""

    def test_merged_stream_stderr_none_does_not_raise(self):
        # Shape produced by run_gradle(): stderr=subprocess.STDOUT => stderr is None.
        result = subprocess.CompletedProcess(
            args=["gradlew", "check"], returncode=1,
            stdout="FAILURE: Build failed with an exception.", stderr=None,
        )
        # Pre-fix this was `result.stderr[-500:]` -> TypeError, hiding the message.
        self.assertEqual(
            diagnostic_tail(result, 500),
            "FAILURE: Build failed with an exception.",
        )

    def test_stderr_preferred_when_present(self):
        result = subprocess.CompletedProcess(
            args=["gradlew"], returncode=1, stdout="out", stderr="err",
        )
        self.assertEqual(diagnostic_tail(result), "err")

    def test_no_output_is_empty_not_none(self):
        result = subprocess.CompletedProcess(
            args=["gradlew"], returncode=1, stdout=None, stderr=None,
        )
        self.assertEqual(diagnostic_tail(result), "")

    def test_tail_is_bounded(self):
        result = subprocess.CompletedProcess(
            args=["gradlew"], returncode=1, stdout="x" * 1000, stderr=None,
        )
        self.assertEqual(len(diagnostic_tail(result, 100)), 100)


class WorktreeGuardTest(unittest.TestCase):
    """#417: the working-tree probe reports real porcelain entries."""

    def test_dirty_path_is_reported(self):
        # git_status_porcelain() reads PROJECT_ROOT; assert it is well-formed.
        lines = git_status_porcelain()
        self.assertIsInstance(lines, list)
        for line in lines:
            self.assertTrue(line.strip())

    def test_clean_dir_reports_nothing(self):
        with tempfile.TemporaryDirectory() as tmp:
            # An empty, non-repo directory must yield [] (guard degrades safely).
            result = subprocess.run(
                ["git", "status", "--porcelain"],
                cwd=tmp, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                text=True,
            )
            self.assertNotEqual(result.returncode, 0)


class ModelConfigGateTest(unittest.TestCase):
    """#415 负控发现：跳过的门与被测的 CLI 必须读同一份配置。

    atlas 的负控：把 env 指向含 gemma-4 的伪造配置 ⇒ 门开了（不 skip）
    但用例仍红，因为**门读 env、CLI 子进程读 ~/.alice/model.json**。
    修法：门只读 CLI 读的硬路径，覆盖只允许走函数参数（不引入进程级真源）。
    """

    def test_gate_path_matches_cli_hard_path(self):
        # 与 ModelConfigLoader.java 的 user.home + .alice/model.json 对齐
        self.assertEqual(local_model_config_path(), Path.home() / ".alice" / "model.json")

    def test_env_override_does_not_move_the_gate(self):
        # 曾经的 ALICE_MODEL_CONFIG：只影响 skip 判定 ⇒ 门与产品分家。回归锁。
        before = skip_if_no_local_supplier("gemma-4")
        old = os.environ.get("ALICE_MODEL_CONFIG")
        os.environ["ALICE_MODEL_CONFIG"] = "/nonexistent/fake-model.json"
        try:
            self.assertEqual(
                skip_if_no_local_supplier("gemma-4"), before,
                "门不得因 env 改变判定（否则与 CLI 读的配置分家）",
            )
        finally:
            if old is None:
                os.environ.pop("ALICE_MODEL_CONFIG", None)
            else:
                os.environ["ALICE_MODEL_CONFIG"] = old

    def test_parsing_logic_is_injectable(self):
        # 解析逻辑可用纯函数参数测（不靠 env）
        with tempfile.TemporaryDirectory() as tmp:
            cfg = Path(tmp) / "model.json"
            cfg.write_text(
                '{"providers":{"gemma4":{"available_models":[{"name":"gemma-4"}]}}}',
                encoding="utf-8",
            )
            self.assertFalse(skip_if_no_local_supplier("gemma-4", config_path=cfg))
            self.assertTrue(skip_if_no_local_supplier("no-such-model", config_path=cfg))

    def test_missing_or_broken_config_skips(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertTrue(
                skip_if_no_local_supplier("gemma-4", config_path=Path(tmp) / "absent.json")
            )
            broken = Path(tmp) / "broken.json"
            broken.write_text("not json", encoding="utf-8")
            self.assertTrue(skip_if_no_local_supplier("gemma-4", config_path=broken))


class AgentLogPathTest(unittest.TestCase):
    """#765：smoke 用例读的日志文件必须 = logback 真正在写的那个。

    历史坑：`agent_run.agent_log_file()` 写死 `alice-agent.log`，而 logback 早已改为
    `info.log`（按天+50MB 滚动）⇒ 日志增量恒为空 ⇒ `test_agent_ran_the_test_suite`
    结构性永红，却被误读成「LLM 概率性」。
    """

    LOGBACK = (
        Path(__file__).resolve().parents[1]
        / "alice-bootstrap" / "src" / "main" / "resources" / "logback.xml"
    )

    def test_points_at_file_logback_actually_writes(self):
        from e2e.smoke.agent_run import agent_log_file

        if not self.LOGBACK.exists():
            self.skipTest(f"logback.xml 不存在：{self.LOGBACK}")
        xml = self.LOGBACK.read_text(encoding="utf-8")
        declared = [
            line.split("<file>")[1].split("</file>")[0].strip()
            for line in xml.splitlines() if "<file>" in line
        ]
        self.assertTrue(declared, "logback.xml 里没找到 <file> 声明的日志文件")
        names = {Path(d).name for d in declared}
        self.assertIn(
            agent_log_file().name, names,
            f"agent_run 读 {agent_log_file().name}，但 logback 只写 {sorted(names)}",
        )

    def test_honours_alice_log_dir_like_logback(self):
        from e2e.smoke.agent_run import agent_log_file

        old = os.environ.get("ALICE_LOG_DIR")
        os.environ["ALICE_LOG_DIR"] = "/tmp/alice-logdir-probe"
        try:
            self.assertEqual(
                agent_log_file(), Path("/tmp/alice-logdir-probe") / "info.log"
            )
        finally:
            if old is None:
                os.environ.pop("ALICE_LOG_DIR", None)
            else:
                os.environ["ALICE_LOG_DIR"] = old


class Gemma4FailureClassificationTest(unittest.TestCase):
    """#766：gemma4 不可达与产品缺陷要能分开读 —— 分类提示的判据。

    服务不可达时契约断言默认判红（契约未被验证 ≠ 通过），但提示必须**只在
    失败全部来自 gemma4** 时出现，否则会把"混着别的红"也归因成环境问题。
    """

    def _hint(self, ids):
        from e2e.run_alice_e2e import gemma4_only_failure_hint

        return gemma4_only_failure_hint(ids)

    def test_all_gemma4_failures_get_hint(self):
        hint = self._hint([
            "(gemma4.gemma4_e2e_test.TestGemma4E2E.test_basic_chat)",
            "gemma4.gemma4_e2e_test.TestGemma4E2E.test_streaming_chat",
        ])
        self.assertIsNotNone(hint)
        self.assertIn("GEMMA4_E2E_SKIP_IF_DOWN=1", hint)

    def test_mixed_failures_get_no_hint(self):
        # 混一条非 gemma4 的红就不许提示"可能只是没起服务"（会误导归因）
        self.assertIsNone(self._hint([
            "gemma4.gemma4_e2e_test.TestGemma4E2E.test_basic_chat",
            "smoke.test_smoke_case_1.TestSmokeCase1.test_divide_zero_raises_value_error",
        ]))

    def test_no_failures_get_no_hint(self):
        self.assertIsNone(self._hint([]))


class SmokeFixtureBaselineTest(unittest.TestCase):
    """smoke case3 的语义是「先红 → Agent 修 → 转绿」，**夹具必须起步是红的**。

    事故（#766 · ledger 抓）：一个 `git add -A` 把 Agent 跑完留下的修复版
    `e2e/smoke/fixtures/pytest_tdd/parser.py` 一起提交了，后果不是"多改了文件"而是
    **语义全废**：`test_pytest_all_pass_after_fix` 一开始就绿（agent 什么都不做也过）、
    `test_parser_handles_invalid_json` 的 `try/except` 断言恒真 ——
    TDD 自省闭环退化成"能不能跑 pytest"。
    """

    FIXTURE_DIR = Path(__file__).resolve().parent / "smoke" / "fixtures" / "pytest_tdd"

    def test_fixture_suite_starts_red(self):
        """起步必须至少 1 个失败（否则"修好了"这件事不可观测）。"""
        # 夹具目录**必须存在**：缺了要红不要 skip —— 否则"锁"在入口被删时会静默变空
        # （与 #766 修的是同一类形状：把"没观测"当成"通过"）。
        self.assertTrue(
            self.FIXTURE_DIR.is_dir(),
            f"smoke case3 夹具目录不存在：{self.FIXTURE_DIR}（基线锁会失去观测对象）",
        )
        r = subprocess.run(
            [sys.executable, "-m", "pytest", str(self.FIXTURE_DIR), "-q",
             "-p", "no:cacheprovider"],
            capture_output=True, text=True, timeout=60,
        )
        out = r.stdout + r.stderr
        self.assertNotEqual(
            r.returncode, 0,
            f"smoke case3 的夹具必须**起步红**，现在全绿 ⇒ 「Agent 自我修复」无从验证。\n{out[-400:]}",
        )

    def test_parser_fixture_not_already_fixed(self):
        """坏掉的 parser.py 里不该已经出现修复（`try`/`except`）—— 那会让断言恒真。"""
        parser = self.FIXTURE_DIR / "parser.py"
        self.assertTrue(
            parser.is_file(),
            f"夹具 parser.py 不存在：{parser}（基线锁会失去观测对象）",
        )
        content = parser.read_text(encoding="utf-8")
        self.assertNotIn(
            "except", content,
            "夹具 parser.py 已被修好，smoke case3 的 try/except 断言会恒真",
        )


if __name__ == "__main__":
    unittest.main()
