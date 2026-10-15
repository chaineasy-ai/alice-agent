#!/usr/bin/env python3
"""gate.py — 提交门禁 runner (通用质量门禁, L1 校验)

由 gate.yaml 声明检查器: 文档合规 / 目录结构 / 格式化 / 测试 / 覆盖率...
  pre-commit 只跑 stage=fast 且命中暂存文件的检查器 (秒级)
  CI 跑 stage=all (全量)

gate.yaml 格式:
  gates:
    <名称>:
      cmd: "<shell 命令>"          # 任意命令检查器 (退出码 0=通过)
      type: structure              # 或内置目录结构检查器
      require: [目录]              #   (structure) 必须存在的目录
      forbid:  [目录]              #   (structure) 禁止存在的路径
      on: ["glob 模式"]            # 暂存文件命中任一模式才运行 (pre-commit 场景)
      stage: fast | slow           # fast=提交也跑; slow=仅 CI (默认 fast)

用法:
  python3 tools/gate.py [--stage fast|slow|all] [--changed] [--config gate.yaml] [--quiet]
退出码: 0=全部通过, 1=存在失败
"""

import argparse
import fnmatch
import os
import re
import subprocess
import sys

DEFAULT_CONFIG = "gate.yaml"


def parse_gates(text):
    """极简 YAML 子集解析 (零依赖): gates -> 名称 -> {key: value|list}。
    支持: 顶层 (0) / gate 名 (2) / 字段 (4, 空值视为列表占位) / 列表项 (6)。"""
    gates, cur, list_key = {}, None, None
    for raw in text.splitlines():
        s = raw.strip()
        if not s or s.startswith("#"):
            continue
        indent = len(raw) - len(raw.lstrip())
        if indent == 0:
            cur, list_key = None, None
        elif indent == 2:
            m = re.match(r"^([\w-]+):\s*(.*)$", s)
            if m:
                cur = m.group(1)
                gates.setdefault(cur, {})
                list_key = None
        elif indent == 4 and cur:
            m = re.match(r"^([\w-]+):\s*(.*)$", s)
            if m:
                key, val = m.group(1), m.group(2).strip().strip('"').strip("'")
                if val:
                    gates[cur][key] = val
                    list_key = None
                else:
                    gates[cur][key] = []  # 空值 -> 列表占位
                    list_key = key
            else:
                list_key = None
        elif indent == 6 and cur and list_key:
            m = re.match(r"^-\s+(.+)$", s)
            if m:
                gates[cur][list_key].append(m.group(1).strip('"').strip("'"))
        else:
            list_key = None
    return gates


def load_config(path):
    if not os.path.isfile(path):
        return None
    with open(path, encoding="utf-8") as fh:
        return parse_gates(fh.read())


def config_problems(path):
    """检测配置中【不会被解析执行】的顶层块（门禁静默丢失）。

    解析约定: 顶层(0) 只能有 `gates:`；gate 名(2)；字段(4)；列表项(6)。
    若出现其它 indent==0 的键（典型: 某个 gate 名被误写成顶格），
    parse_gates 会静默忽略整块 ⇒ 该门禁形同不存在。此处显式报出。"""
    problems = []
    if not os.path.isfile(path):
        return problems
    try:
        with open(path, encoding="utf-8") as fh:
            text = fh.read()
    except OSError:
        return problems
    for i, raw in enumerate(text.splitlines(), 1):
        s = raw.strip()
        if not s or s.startswith("#"):
            continue
        if len(raw) - len(raw.lstrip()) == 0 and s != "gates:":
            m = re.match(r"^([\w-]+):", s)
            if m:
                problems.append(
                    f"行 {i}: 顶层键 '{m.group(1)}' 不在 'gates:' 下（缩进应为 2）"
                    f" → 该块不会被解析，门禁静默丢失"
                )
    return problems


def staged_files(root):
    r = subprocess.run(
        ["git", "-C", root, "diff", "--cached", "--name-only"],
        capture_output=True,
        text=True,
    )
    return [f for f in r.stdout.splitlines() if f]


def check_structure(cfg, root):
    errs = []
    for d in cfg.get("require", []) or []:
        if not os.path.isdir(os.path.join(root, d)):
            errs.append(f"missing dir: {d}")
    for d in cfg.get("forbid", []) or []:
        if os.path.exists(os.path.join(root, d)):
            errs.append(f"forbidden path exists: {d}")
    return errs


def _modes(args):
    """git ls-files -s / ls-tree -r HEAD → {path: mode(如 100755)}。"""
    r = subprocess.run(args, capture_output=True, text=True)
    out = {}
    for ln in r.stdout.splitlines():
        if "\t" not in ln:
            continue
        meta, path = ln.split("\t", 1)
        parts = meta.split()
        if parts:
            out[path] = parts[0]
    return out


def check_exec_bit(root, changed):
    """防复发 (#456/#507): 可执行位回归守护。

    HEAD 里 mode=100755 的文件，若在 index 里被改成非 100755
    （典型: 工具重写文件时把 exec 位洗成 644）⇒ 报错，阻止提交。
    只报【回归】，不要求新增脚本必须 +x（避免对既有 644 归档脚本造成噪声）。"""
    errs = []
    idx = _modes(["git", "-C", root, "ls-files", "-s"])
    head = _modes(["git", "-C", root, "ls-tree", "-r", "HEAD"])
    for path, m in idx.items():
        if changed is not None and path not in changed:
            continue
        if head.get(path) == "100755" and m != "100755":
            errs.append(f"{path}: HEAD=100755 -> index={m}（可执行位被洗掉）")
    return errs


def run_gate(name, cfg, root, stage, changed):
    if cfg.get("stage") == "slow" and stage != "all":
        return "SKIP(ci-only)", True
    if changed is not None:
        pats = cfg.get("on") or ["**"]
        if not any(fnmatch.fnmatch(f, p) for p in pats for f in changed):
            return "SKIP(no-match)", True
    if cfg.get("type") == "structure":
        errs = check_structure(cfg, root)
        if errs:
            print(f"FAIL {name}: {'; '.join(errs)}")
            return "FAIL", False
        return "PASS", True
    cmd = cfg.get("cmd")
    if not cmd:
        print(f"FAIL {name}: missing cmd/type")
        return "FAIL", False
    r = subprocess.run(cmd, shell=True, cwd=root, capture_output=True, text=True)
    if r.returncode != 0:
        out = (r.stdout + r.stderr).strip()
        print(f"FAIL {name}: {cmd}")
        if out:
            for ln in out.splitlines()[:15]:
                print(f"    {ln}")
        return "FAIL", False
    return "PASS", True


def main():
    ap = argparse.ArgumentParser(description="提交门禁 runner (L1)")
    ap.add_argument("--stage", default="all", choices=["fast", "slow", "all"])
    ap.add_argument(
        "--changed",
        action="store_true",
        help="只跑命中暂存文件的检查器 (pre-commit 场景)",
    )
    ap.add_argument("--config", default=DEFAULT_CONFIG)
    ap.add_argument("--quiet", action="store_true")
    args = ap.parse_args()

    root = os.getcwd()

    # 防复发 (#456): 可执行位回归守护（无 gate.yaml 也生效；仅报 HEAD 755 -> 非 755）
    changed = staged_files(root) if args.changed else None
    exec_errs = check_exec_bit(root, changed)
    if exec_errs:
        print("FAIL exec-bit: 可执行位回归（HEAD 100755 -> 现非 100755）:")
        for e in exec_errs:
            print(f"    {e}")
        print("    修: git update-index --chmod=+x <file> 后再提交")
        return 1

    cfg_path = os.path.join(root, args.config)
    gates = load_config(cfg_path)
    if gates is None:
        print(f"no config: {args.config} (skip)")
        return 0

    # 防静默丢门禁: 存在不会被解析的顶层块 → 直接拒绝 (见 #507)
    problems = config_problems(cfg_path)
    if problems:
        print(f"FAIL {args.config}: 存在不会被解析的顶层块（门禁静默丢失）:")
        for pb in problems:
            print(f"    {pb}")
        return 1

    n_pass = n_skip = n_fail = 0
    for name, cfg in gates.items():
        if args.quiet and cfg.get("stage") == "slow" and args.stage != "all":
            continue
        status, ok = run_gate(name, cfg, root, args.stage, changed)
        if not args.quiet:
            print(f"{status:<14} {name}")
        if status == "PASS":
            n_pass += 1
        elif status == "SKIP(no-match)":
            n_skip += 1
        elif status == "SKIP(ci-only)":
            n_skip += 1
        else:
            n_fail += 1
    if not args.quiet:
        print(f"gate: pass={n_pass} skip={n_skip} fail={n_fail} (stage={args.stage})")
    return 1 if n_fail else 0


if __name__ == "__main__":
    sys.exit(main())
