"""
PMTEV Smoke Test Framework for Alice Agent.

Implements the 3 standard smoke test cases defined in:
  docs/Agent 冒烟测试用例规范文档.md

PMTEV = Plan, Model, Tool, Environment, Verification

Files:
  test_smoke_case_1.py   — 基础工具与文件编辑闭环测试
  test_smoke_case_2.py   — 多工具协同与跨文件依赖修复测试
  test_smoke_case_3.py   — 沙箱环境执行与TDD自省闭环测试
  runner.py              — batch driver to execute all cases
  config.py              — shared SmokeCase dataclass & paths
"""
