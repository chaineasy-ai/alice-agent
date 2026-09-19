#!/usr/bin/env python3
"""
Alice Agent 冒烟用例共享驱动。

职责：
  - ``run_agent()``      —— 调用一次 Alice Agent 并返回退出码与输出
  - ``snapshot_files()`` / ``restore_files()`` —— 在用例前后保护 fixture 文件

为什么不再使用 ``--rerun-tasks``：
    ``:alice-bootstrap:run --rerun-tasks`` 会让 Gradle 重跑整条任务图（实测 90 个
    task，覆盖全部 12 个模块的编译），每次调用都要多花几分钟，很容易把用例自身
    的超时预算吃光 —— 表现上就是"Agent 请求没反应"。Gradle 的增量构建已经能保证
    源码变更后重新编译，因此这里只依赖增量构建。

为什么快照/恢复 fixture 而不依赖 ``git checkout``：
    用例被中断（Ctrl-C、超时被杀）时 tearDown 不会执行，fixture 会残留 Agent 的
    修改，下一轮用例的起点就不干净了。这里改为在内存里快照文件字节并直接写回。

Spec reference: docs/Agent 冒烟测试用例规范文档.md
"""

from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[2]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from e2e.helpers import GRADLEW  # noqa: E402
from e2e.smoke.config import DEEPSEEK_API_KEY, PROJECT_MODEL  # noqa: E402


# ── Agent 调用 ─────────────────────────────────────────────────────────────


def build_agent_command(prompt: str, model: str, task_timeout: int) -> list[str]:
    """构造调用 Alice Agent 的命令行。

    prompt 会被压平成单行，再交给 Gradle 的 ``--args`` 解析；Gradle 用双引号划分
    参数，所以 prompt 本身不能包含双引号。

    :param prompt: 用户输入（problem_description）
    :param model: 目标模型 ID
    :param task_timeout: 传给 CLI 的任务超时秒数（CLI 默认仅 180s，多步任务会被腰斩）
    :return: 可直接交给 ``subprocess`` 的命令行数组
    :raises ValueError: prompt 含双引号，无法安全传给 Gradle
    """
    prompt_flat = " ".join(prompt.split())
    if '"' in prompt_flat:
        raise ValueError("prompt 不能包含双引号：Gradle --args 无法转义。")
    return [
        str(GRADLEW),
        ":alice-bootstrap:run",
        "--console=plain",
        "--args",
        f'run "{prompt_flat}" --model {model} --timeout {task_timeout} --verbose',
    ]


def agent_log_file() -> Path:
    """Alice Agent 的文件日志路径（logback 配置在 alice-bootstrap/src/main/resources）。"""
    return Path.home() / ".alice" / "logs" / "alice-agent.log"


def _read_log_delta(offset: int) -> str:
    """读取 Agent 文件日志自 ``offset`` 之后新增的内容。

    工具调用细节（工具名、参数、结果）只写在文件日志里，不在 stdout，
    因此校验"Agent 是否真的调用了 grep / pytest"需要看这段增量。
    """
    log = agent_log_file()
    if not log.exists():
        return ""
    try:
        with log.open("r", encoding="utf-8", errors="replace") as fh:
            fh.seek(offset)
            return fh.read()
    except OSError:
        return ""


def run_agent(
    prompt: str,
    model: str | None = None,
    timeout: int = 300,
) -> tuple[int, str, str]:
    """调用一次 Alice Agent。

    :param prompt: 用户输入（problem_description）
    :param model: 目标模型 ID，默认取 ``config.PROJECT_MODEL``
    :param timeout: 整个子进程（含 Gradle 增量构建）的超时秒数；CLI 自身的任务超时
        会按此值的 80% 下发，以保证超时以"任务超时"而非"进程被杀"的形式出现
    :return: ``(exit_code, stdout+stderr, agent 文件日志增量)``；进程超时返回 ``124``
    """
    model = model or PROJECT_MODEL
    env = os.environ.copy()
    if DEEPSEEK_API_KEY:
        env["DEEPSEEK_API_KEY"] = DEEPSEEK_API_KEY

    task_timeout = max(60, int(timeout * 0.8))
    cmd = build_agent_command(prompt, model, task_timeout)
    print(f"\n  🚀 Alice Agent: model={model} task_timeout={task_timeout}s")
    print(f"     prompt={prompt[:80]}...")

    log = agent_log_file()
    log_offset = log.stat().st_size if log.exists() else 0

    try:
        result = subprocess.run(
            cmd,
            cwd=PROJECT_ROOT,
            capture_output=True,
            text=True,
            timeout=timeout,
            env=env,
            # 不继承 stdin：CLI 一旦回退到交互模式就会永久阻塞在 readLine 上。
            stdin=subprocess.DEVNULL,
        )
    except subprocess.TimeoutExpired as exc:
        stdout = exc.stdout or ""
        stderr = exc.stderr or ""
        if isinstance(stdout, bytes):
            stdout = stdout.decode("utf-8", errors="replace")
        if isinstance(stderr, bytes):
            stderr = stderr.decode("utf-8", errors="replace")
        print(f"  ⏱️  Agent 超时（{timeout}s）")
        return (
            124,
            f"{stdout}{stderr}\n[timeout] Agent 超过 {timeout}s 未结束。",
            _read_log_delta(log_offset),
        )

    output = result.stdout + result.stderr
    print(f"  {'✅' if result.returncode == 0 else '⚠️ '} Agent exit={result.returncode}")
    return result.returncode, output, _read_log_delta(log_offset)


# ── Fixture 快照 / 恢复 ────────────────────────────────────────────────────


def snapshot_files(paths) -> dict[Path, bytes]:
    """读取文件当前内容，供用例结束后恢复。

    :param paths: 需要保护的文件路径集合
    :return: ``{路径: 字节内容}``，不存在的文件会被跳过
    """
    snapshot: dict[Path, bytes] = {}
    for raw in paths:
        path = Path(raw)
        if path.exists():
            snapshot[path] = path.read_bytes()
    return snapshot


def restore_files(snapshot: dict[Path, bytes]) -> None:
    """把文件写回快照时的内容。

    与 ``git checkout`` 不同，恢复动作只依赖内存快照，因此即使仓库里还有其它未提交
    改动也不会被牵连，重复调用也是幂等的。

    :param snapshot: :func:`snapshot_files` 的返回值
    """
    for path, data in snapshot.items():
        if path.exists() and path.read_bytes() == data:
            continue
        path.write_bytes(data)
