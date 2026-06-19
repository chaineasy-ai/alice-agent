"""
PMTEV Smoke Test Cases — definition dataclasses.

Each case is a SmokeCase dataclass with instance_id, problem_description
(from 冒烟测试规范文档), and verification assertions.

See also:
    e2e/smoke/test_smoke_case_*.py — actual unittest implementations
    docs/Agent 冒烟测试用例规范文档.md — specification
"""

from e2e.smoke.config import SmokeCase, FIXTURES_DIR


# ── Case 1: 基础工具与文件编辑闭环测试 ──────────────────────────────────────

CASE_1 = SmokeCase(
    instance_id="smoke__case-1",
    repo_path=FIXTURES_DIR / "math_utils",
    problem_description=(
        "修复 e2e/smoke/fixtures/math_utils/math_utils.py 中的 "
        "divide 函数。当除数为 0 时，目前程序会直接崩溃，"
        "请修改为抛出 ValueError('Division by zero is not allowed')。"
    ),
    assertions=[
        "Agent 执行了 PPAO 循环（Plan → Process → Act → Observe）",
        "Agent 输出了 Final Answer 或 Action 结果",
    ],
    timeout_seconds=120,
)

# ── Case 2: 多工具协同与跨文件依赖修复测试 ──────────────────────────────────

CASE_2 = SmokeCase(
    instance_id="smoke__case-2",
    repo_path=FIXTURES_DIR / "cross_file_config",
    problem_description=(
        "废弃 e2e/smoke/fixtures/cross_file_config/config.py 内旧配置项 "
        "TIMEOUT_MS（单位：毫秒），统一替换为 TIMEOUT_SEC（单位：秒）。\n"
        "1. 重命名 config.py 内部字段；\n"
        "2. 同步修改 e2e/smoke/fixtures/cross_file_config/client.py 中所有引用"
        "该超时配置的代码；\n"
        "3. 正确完成单位换算：1秒 = 1000毫秒。"
    ),
    assertions=[
        "Agent 执行了 PPAO 循环",
        "Agent 输出了 Final Answer",
    ],
    timeout_seconds=300,
)

# ── Case 3: 沙箱环境执行与TDD自省闭环测试 ──────────────────────────────────

CASE_3 = SmokeCase(
    instance_id="smoke__case-3",
    repo_path=FIXTURES_DIR / "pytest_tdd",
    problem_description=(
        "执行 e2e/smoke/fixtures/pytest_tdd 目录的单元测试，"
        "当前 test_payload_parsing 用例执行失败。"
        "读取报错堆栈信息，定位 parser.py 业务代码缺陷并修复，"
        "保证本地执行 pytest 全部用例通过。"
    ),
    assertions=[
        "Agent 执行了 PPAO 循环",
        "Agent 输出了 Final Answer",
    ],
    timeout_seconds=600,
)

# ── Case Registry ──────────────────────────────────────────────────────────

SMOKE_CASES = [CASE_1, CASE_2, CASE_3]

SMOKE_CASES_BY_ID = {c.instance_id: c for c in SMOKE_CASES}
