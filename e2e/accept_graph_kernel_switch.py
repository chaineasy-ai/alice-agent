#!/usr/bin/env python3
"""
#297 验收硬件 — graphKernel 开关（CLI 会话 on/off）端到端判据。

口径（atlas grant，见 base/cland-crawler#306 / #297）：
  配置键 `graphKernel.enabled` + CLI `--graph-kernel[=on|off]`（优先级 CLI > 配置 > 默认）
  默认 on（图内核=目标态；off 仅回退）；启动横幅/日志打印“生效值 + 来源”。

本脚本在 **开关落地前** 运行会报告各用例 FAIL（未识别 `--graph-kernel`），
落地后应全 PASS；可直接作为 #306 的回归验收器。

用法：
  python3 e2e/accept_graph_kernel_switch.py            # 全用例
  python3 e2e/accept_graph_kernel_switch.py --quick     # 仅 CLI on/off + 功能

判据（逐用例 PASS/FAIL）：
  A1 CLI --graph-kernel=on   → 横幅 graphKernel=true · source=cli · exit 0
  A2 CLI --graph-kernel=off  → 横幅 graphKernel=false · source=cli
  A3 默认（无 flag/无配置）   → graphKernel=true（默认 on）
  A4 配置 graphKernel.enabled=true（无 flag）  → graphKernel=true · source=config
  A5 CLI 优先：配置 off + CLI on → graphKernel=true · source=cli
  A6 功能：on 会话完成工具任务（根目录 .md 计数）→ 答案含正确数字
"""
import json
import os
import re
import sys
import unittest
from pathlib import Path

E2E_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(E2E_DIR))
from helpers import PROJECT_ROOT, run_cli  # noqa: E402

CONFIG_PATH = Path.home() / ".alice" / "config.json"
TASK = "列出本项目根目录下 .md 文件的数量，只回答数字"
EXPECTED_MD = len(list((PROJECT_ROOT).glob("*.md")))


def _combined(result):
    return (result.stdout or "") + (result.stderr or "")


def _effective(output):
    """解析横幅/日志中的生效值 + 来源。"""
    val = re.search(r"graphKernel\s*=\s*(true|false)", output, re.I)
    src = re.search(r"(?:source|来源)\s*[=:]\s*(cli|config|default)", output, re.I)
    return (val.group(1).lower() if val else None, src.group(1).lower() if src else None)


class _ConfigGuard:
    def __enter__(self):
        self._backup = CONFIG_PATH.read_text(encoding="utf-8") if CONFIG_PATH.exists() else None
        return self

    def __exit__(self, *exc):
        if self._backup is None:
            CONFIG_PATH.unlink(missing_ok=True)
        else:
            CONFIG_PATH.parent.mkdir(parents=True, exist_ok=True)
            CONFIG_PATH.write_text(self._backup, encoding="utf-8")


def _write_config(enabled):
    CONFIG_PATH.parent.mkdir(parents=True, exist_ok=True)
    data = {}
    if CONFIG_PATH.exists():
        try:
            data = json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
        except Exception:
            data = {}
    # 口径键 graphKernel.enabled（同时写嵌套与扁平，兼容 #306 最终映射）
    data.setdefault("graphKernel", {})["enabled"] = enabled
    data["graphKernel_enabled"] = enabled
    CONFIG_PATH.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


class GraphKernelSwitchAcceptance(unittest.TestCase):
    maxDiff = None

    @classmethod
    def setUpClass(cls):
        cls.guard = _ConfigGuard().__enter__()

    @classmethod
    def tearDownClass(cls):
        cls.guard.__exit__(None, None, None)

    def _run(self, extra_args, timeout=240):
        r = run_cli(["run", TASK, *extra_args], timeout=timeout, module=":alice-facade-cli:run")
        out = _combined(r)
        val, src = _effective(out)
        print(f"\n  ↳ exit={r.returncode} graphKernel={val} source={src}")
        return r, out, val, src

    def test_A1_cli_on(self):
        CONFIG_PATH.unlink(missing_ok=True)
        r, out, val, src = self._run(["--graph-kernel=on"])
        self.assertEqual(r.returncode, 0, out[-400:])
        self.assertEqual(val, "true", "CLI on 应 graphKernel=true")
        self.assertEqual(src, "cli", "来源应为 cli")

    def test_A2_cli_off(self):
        CONFIG_PATH.unlink(missing_ok=True)
        r, out, val, src = self._run(["--graph-kernel=off"])
        self.assertEqual(r.returncode, 0, out[-400:])
        self.assertEqual(val, "false", "CLI off 应 graphKernel=false")
        self.assertEqual(src, "cli")

    def test_A3_default_on(self):
        CONFIG_PATH.unlink(missing_ok=True)
        r, out, val, src = self._run([])
        self.assertEqual(r.returncode, 0, out[-400:])
        self.assertEqual(val, "true", "默认应为 on（换轨目标态）")

    def test_A4_config_true(self):
        _write_config(True)
        r, out, val, src = self._run([])
        self.assertEqual(r.returncode, 0, out[-400:])
        self.assertEqual(val, "true")
        self.assertEqual(src, "config")

    def test_A5_cli_overrides_config(self):
        _write_config(False)
        r, out, val, src = self._run(["--graph-kernel=on"])
        self.assertEqual(r.returncode, 0, out[-400:])
        self.assertEqual(val, "true")
        self.assertEqual(src, "cli", "CLI 优先级 > 配置")

    def test_A6_functional_on_session(self):
        CONFIG_PATH.unlink(missing_ok=True)
        r, out, val, src = self._run(["--graph-kernel=on"])
        self.assertEqual(r.returncode, 0, out[-400:])
        self.assertRegex(out, rf"\b{EXPECTED_MD}\b",
                         f"on 会话应完成工具任务并给出根目录 .md 计数 {EXPECTED_MD}")

    def test_A7_exec_mode_marker(self):
        """横幅 exec 标注：on -> exec=graph，off -> exec=legacy（#313-A）。"""
        CONFIG_PATH.unlink(missing_ok=True)
        r_on, out_on, _, _ = self._run(["--graph-kernel=on"])
        self.assertEqual(r_on.returncode, 0, out_on[-400:])
        self.assertIn("exec=graph", out_on, "on 应标注 exec=graph")

        r_off, out_off, _, _ = self._run(["--graph-kernel=off"])
        self.assertEqual(r_off.returncode, 0, out_off[-400:])
        self.assertIn("exec=legacy", out_off, "off 应标注 exec=legacy")


if __name__ == "__main__":
    print("=" * 64)
    print(f"graphKernel 开关验收 · 期望根目录 .md 数 = {EXPECTED_MD}")
    print("=" * 64)
    unittest.main(verbosity=2)
