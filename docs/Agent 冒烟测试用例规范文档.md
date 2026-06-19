# Agent 冒烟测试用例规范文档
## 核心框架说明
为自研 Agent 设计冒烟测试（Smoke Test）用例，需遵循 **PMTEV 闭环框架**：规划（Plan）、模型（Model）、工具（Tool）、环境（Environment）、验证（Verification）。
冒烟测试核心目标：不做极限压力测试，仅完整校验工具调用链路、上下文管理、本地验证流程全链路可正常运行。

下文面向软件工程/代码修复类 Agent，提供3套梯度化标准冒烟测试用例，内容可直接作为入参 `problem_description` 传入 Agent。

---

## 测试用例集
### Case 1 基础工具与文件编辑闭环测试（基础级简易缺陷修复）
#### 测试目标
校验 Agent 文件检索、行号定位、文件编辑工具调用能力，输出合规 Git 差异补丁。
#### 前置环境
仓库内置简易数学工具文件 `math_utils.py`。
#### 用户输入（problem_description）
```
修复 math_utils.py 中的 divide 函数。当除数为 0 时，目前程序会直接崩溃，请修改为抛出 ValueError('Division by zero is not allowed')。
```
#### 通过校验断言
1. 输出产物 `predictions.jsonl` 内 `model_patch` 字段包含关键字 `raise ValueError`；
2. 输出补丁为标准 Git Diff 格式，无多余自然文本描述。

---

### Case 2 多工具协同与跨文件依赖修复测试（中级跨文件逻辑变更）
#### 测试目标
校验 Agent 任务规划（Plan）能力，可跨文件全局检索变量、同步修改多处关联代码。
#### 前置环境
仓库包含配置文件 `config.py`、业务逻辑文件 `client.py`。
#### 用户输入（problem_description）
```
废弃 config.py 内旧配置项 TIMEOUT_MS（单位：毫秒），统一替换为 TIMEOUT_SEC（单位：秒）。
1. 重命名 config.py 内部字段；
2. 同步修改 client.py 中所有引用该超时配置的代码；
3. 正确完成单位换算：1秒 = 1000毫秒。
```
#### 通过校验断言
1. `model_patch` 同时包含 `config.py`、`client.py` 两处文件修改；
2. Agent 调用日志存在全局检索工具（grep / find）搜索 `TIMEOUT_MS` 的执行记录。

---

### Case 3 沙箱环境执行与TDD自省闭环测试（高级自测纠错流程）
#### 测试目标
校验 Agent 环境执行、结果验证（Execution & Verification）能力：可在沙箱运行单元测试、读取报错堆栈、自我迭代修复代码直至测试通过。
#### 前置环境
Python 项目，基于 pytest 编写单元测试；预置存在逻辑缺陷，执行 pytest 会抛出 AssertionError。
#### 用户输入（problem_description）
```
执行仓库单元测试，当前 test_payload_parsing 用例执行失败。读取报错堆栈信息，定位 parser.py 业务代码缺陷并修复，保证本地执行 pytest 全部用例通过。
```
#### 通过校验断言
1. Agent 工具调用日志至少包含一次终端执行命令（pytest / python -m unittest），用于捕获测试报错；
2. 首次修复未通过测试时，可触发自省（Reflection）逻辑，二次迭代修改代码。

---

# 本地冒烟测试执行集成方案
无需部署完整 SWE-bench Docker 评测集群，本地开发环境可通过轻量脚本驱动全部测试用例。
## 驱动示例代码
```python
import subprocess
import json

# 定义全部冒烟测试用例
smoke_cases = [
    {
        "instance_id": "smoke__case-1",
        "repo_path": "/home/developer/workspace/my-test-repo",
        "problem_description": "修复 math_utils.py 中的 divide 函数。当除数为 0 时，抛出 ValueError('Division by zero is not allowed')。"
    }
]

# 批量执行冒烟用例
for case in smoke_cases:
    print(f"开始执行冒烟用例：{case['instance_id']}")
    
    # 调用自研Agent，授予目标仓库读写、测试命令执行权限
    model_patch = run_alice_agent(
        target_dir=case["repo_path"],
        prompt=case["problem_description"]
    )

    # 基础输出格式校验
    if not model_patch.startswith("diff --git"):
        print("❌ 校验失败：Agent 未输出标准 Git Diff 补丁")
        continue
    print("✅ 校验通过：全链路闭环正常，输出合规补丁")
```

## 使用流程建议
1. 优先执行3套冒烟用例，打通完整链路：指令解析 → 文件检索工具调用 → 代码修改 → 标准化补丁输出；
2. 冒烟测试全部校验通过后，再接入 SWE-bench 大规模评测集群，大幅降低整体调试成本。
