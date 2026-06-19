---
title: "E2E Scene — CLI Sub-commands"
summary: "验证 alice-facade-cmd 的 6 个 CLI 子命令（run/chat/tools/config/routine/sub-agent）的 picocli 参数解析与分发。对应 AgentCommand 密封指令的 CLI 输入路径。"
read_when:
  - "designing or implementing E2E tests for CLI sub-commands"
  - "verifying picocli argument parsing for run/chat/tools/config/routine/sub-agent"
scope:
  - "alice-facade-cmd"
  - "docs/alice-facade-cmd/e2e/scene-cli-subcommands.md"
status: "active"
updated: "2026-06-19"
---

# E2E Scene — CLI Sub-commands

## 1. 场景描述

验证用户通过 CLI 二进制输入 `alice <subcommand> [options...]` 时，picocli 能正确解析参数并构建 `RunConfig`，最终通过 `dispatchCommand()` 分发给对应的 `AgentCommand`。

### 1.1 输入路径

```
用户终端 → alice run "task" --model gpt-4o
         → picocli @CliRoot.parse(args)
         → RunConfig ————→ ExecutionCoordinator.execute()
         → AliceCliLauncher.dispatchCommand()
         → AgentCommand (AcquireGoalCmd, RegisterRoutineCmd, etc.)
```

### 1.2 覆盖指令

| CLI 子命令 | AgentCommand 类型 | 场景编号 |
|-----------|------------------|---------|
| `alice run <task>` | `AcquireGoalCmd` | TC-CLI-01~05 |
| `alice chat` | `ResetSessionCmd` (入口) | TC-CLI-06~07 |
| `alice tools` | — | TC-CLI-08~10 |
| `alice config` | — | TC-CLI-11~13 |
| `alice routine <cron>` | `RegisterRoutineCmd` | TC-CLI-14~16 |
| `alice sub-agent --spawn` | `SpawnSubAgentCmd` | TC-CLI-17~25 |

### 1.3 不覆盖

- TUI 斜杠命令（由 `AgentCommand.parse()` 处理，见 `docs/alice-facade-tui/e2e/scene-tui-slash-commands.md`）
- `dispatchCommand()` 内部 switch 全路径（见 [scene-dispatch-full-coverage.md](./scene-dispatch-full-coverage.md)）

---

## 2. TC-CLI-01: `alice run` 基础任务

| 字段 | 值 |
|------|-----|
| **命令** | `alice run "say hello"` |
| **预期退出码** | 0 |
| **预期日志** | 包含 `RunConfig`, `task=say hello`, 包含 `AcquireGoalCmd` 或 `dispatch` 记录 |
| **验证点** | `run` 子命令能正确解析任务文本并进入执行协调器 |

### 断言逻辑

```python
result = run_cli(["run", "say hello"], timeout=60)
self.assertIn(result.returncode, [0, 1], msg=result.stderr[:300])
output = result.stdout + result.stderr
self.assertIn("RunConfig", output)
```

---

## 3. TC-CLI-02: `alice run` 模型覆盖

| 字段 | 值 |
|------|-----|
| **命令** | `alice run "task" --model gpt-4o` |
| **预期退出码** | 0 |
| **预期日志** | 包含 `model=gpt-4o`, `modelOverride: gpt-4o` |
| **验证点** | `--model` 参数正确传递至 RunConfig |

---

## 4. TC-CLI-03: `alice run` 布尔标志

| 字段 | 值 |
|------|-----|
| **命令** | `alice run "status" --verbose --json` |
| **预期退出码** | 0 |
| **预期日志** | 包含 `verbose=true`, `json=true` |
| **验证点** | `--verbose` 和 `--json` 布尔标志正确解析 |

---

## 5. TC-CLI-04: `alice run` 缺少 task

| 字段 | 值 |
|------|-----|
| **命令** | `alice run` |
| **预期退出码** | 1（Gradle 包装 app exit 2）或 2（picocli 内部） |
| **预期日志** | 包含 `Missing required parameter` 或 `Missing required argument` |
| **验证点** | picocli 的 `required` 约束生效 |

---

## 6. TC-CLI-05: `alice run --help`

| 字段 | 值 |
|------|-----|
| **命令** | `alice run --help` |
| **预期退出码** | 0 |
| **预期日志** | 包含 `--model`, `--verbose`, `--json` 等选项描述 |
| **验证点** | picocli help 正常显示 |

---

## 7. TC-CLI-06: `alice chat` 交互式会话

| 字段 | 值 |
|------|-----|
| **命令** | `echo "/exit" \| alice chat` |
| **预期退出码** | 0 |
| **预期日志** | 包含 `JLineChatSession`, `Chat session ended` 或 `exit` |
| **验证点** | 交互式 chat 能启动并通过 `/exit` 正常退出 |

---

## 8. TC-CLI-07: `alice chat` 自然语言输入

| 字段 | 值 |
|------|-----|
| **命令** | `echo -e "say hi\n/exit" \| alice chat` |
| **预期退出码** | 0 |
| **预期日志** | 包含 `AcquireGoalCmd`, `goal: say hi` |
| **验证点** | chat 模式中自然语言输入被转换为 AcquireGoalCmd |

---

## 9. TC-CLI-08: `alice tools`

| 字段 | 值 |
|------|-----|
| **命令** | `alice tools` |
| **预期退出码** | 0 |
| **预期日志** | 包含 `Listed tools` 或工具列表 |
| **验证点** | `tools` 子命令能访问 ToolRegistry |

---

## 10. TC-CLI-09: `alice tools --detail`

| 字段 | 值 |
|------|-----|
| **命令** | `alice tools --detail` |
| **预期退出码** | 0 |
| **预期日志** | 包含工具详情（schema 摘要等） |
| **验证点** | `--detail` 标志开启详细模式 |

---

## 11. TC-CLI-10: `alice tools --help`

| 字段 | 值 |
|------|-----|
| **命令** | `alice tools --help` |
| **预期退出码** | 0 |
| **预期日志** | 包含 `--detail` 选项 |
| **验证点** | 帮助文本完整 |

---

## 12. TC-CLI-11: `alice config`

| 字段 | 值 |
|------|-----|
| **命令** | `alice config` |
| **预期退出码** | 0 |
| **预期日志** | 包含配置概览条目（如 `default.model`） |
| **验证点** | `config` 子命令能读取并显示配置 |

---

## 13. TC-CLI-12: `alice config get`

| 字段 | 值 |
|------|-----|
| **命令** | `alice config get default.model` |
| **预期退出码** | 0 |
| **预期日志** | 包含该配置项的值 |
| **验证点** | 键值查询正确 |

---

## 14. TC-CLI-13: `alice config set`

| 字段 | 值 |
|------|-----|
| **命令** | `alice config set openai.api_key sk-test-e2e` |
| **预期退出码** | 0 |
| **预期日志** | 包含 `set openai.api_key` 或 `written` |
| **验证点** | 配置持久化成功 |

---

## 15. TC-CLI-14: `alice routine` 注册 cron

| 字段 | 值 |
|------|-----|
| **命令** | `alice routine "0 */2 * * * ?"` |
| **预期退出码** | 0 |
| **预期日志** | 包含 `routineCron`, `RegisterRoutineCmd` 或 cron 表达式 |
| **验证点** | `routine` 子命令正确解析 cron 参数 |

---

## 16. TC-CLI-15: `alice routine --list`

| 字段 | 值 |
|------|-----|
| **命令** | `alice routine --list` |
| **预期退出码** | 0 |
| **预期日志** | 包含 `listRoutines=true` |
| **验证点** | `--list` 标志正确解析 |

---

## 17. TC-CLI-16: `alice routine --help`

| 字段 | 值 |
|------|-----|
| **命令** | `alice routine --help` |
| **预期退出码** | 0 |
| **预期日志** | 包含 routine 子命令帮助 |
| **验证点** | 帮助文本完整 |

---

## 18. TC-CLI-17: `alice sub-agent --spawn`

| 字段 | 值 |
|------|-----|
| **命令** | `alice sub-agent --spawn "monitor disk usage"` |
| **预期退出码** | 0 |
| **预期日志** | 包含 `subAgentSpawnGoal: monitor disk usage` |
| **验证点** | spawn 目标正确解析 |

---

## 19. TC-CLI-18: `alice sub-agent --list`

| 字段 | 值 |
|------|-----|
| **命令** | `alice sub-agent --list` |
| **预期退出码** | 0 |
| **预期日志** | 包含 `subAgentList: true` |
| **验证点** | `--list` 标志正确解析 |

---

## 20. TC-CLI-19: `alice sub-agent --connect --acp-endpoint`

| 字段 | 值 |
|------|-----|
| **命令** | `alice sub-agent --connect "monitor" --acp-endpoint "http://localhost:9000/acp"` |
| **预期退出码** | 0 |
| **预期日志** | 包含 `subAgentConnectName: monitor`, `endpoint: http://localhost:9000/acp` |
| **验证点** | connect 参数完整解析 |

---

## 21. TC-CLI-20: `alice sub-agent --cancel`

| 字段 | 值 |
|------|-----|
| **命令** | `alice sub-agent --cancel "sub-abc-123"` |
| **预期退出码** | 0 |
| **预期日志** | 包含 `subAgentCancelId: sub-abc-123` |
| **验证点** | cancel ID 正确解析 |

---

## 22. TC-CLI-21: `alice sub-agent --results`

| 字段 | 值 |
|------|-----|
| **命令** | `alice sub-agent --results "sub-abc-123"` |
| **预期退出码** | 0 |
| **预期日志** | 包含 `subAgentResultsId: sub-abc-123` |
| **验证点** | results ID 正确解析 |

---

## 23. TC-CLI-22: `alice sub-agent --send --message`

| 字段 | 值 |
|------|-----|
| **命令** | `alice sub-agent --send "agent1" --message "hello from e2e"` |
| **预期退出码** | 0 |
| **预期日志** | 包含 `subAgentSendId: agent1`, `message: hello from e2e` |
| **验证点** | send + message 参数完整解析 |

---

## 24. TC-CLI-23: `alice sub-agent --prompt --agent-id`

| 字段 | 值 |
|------|-----|
| **命令** | `alice sub-agent --prompt "analyze logs" --agent-id "ext-agent"` |
| **预期退出码** | 0 |
| **预期日志** | 包含 `subAgentPromptAgentId: ext-agent`, `promptText: analyze logs` |
| **验证点** | prompt + agent-id 参数完整解析 |

---

## 25. TC-CLI-24: `alice sub-agent` 空选项

| 字段 | 值 |
|------|-----|
| **命令** | `alice sub-agent` |
| **预期退出码** | 0 |
| **预期日志** | 不崩溃，正常执行 |
| **验证点** | 空 sub-agent 选项不会引发异常 |

---

## 26. TC-CLI-25: `alice sub-agent --help`

| 字段 | 值 |
|------|-----|
| **命令** | `alice sub-agent --help` |
| **预期退出码** | 0 |
| **预期日志** | 包含 sub-agent 各子选项 |
| **验证点** | 帮助文本完整 |

---

## 27. 测试用例汇总

| 编号 | 命令 | 验证核心 |
|------|------|---------|
| TC-CLI-01 | `run "say hello"` | 基础任务解析 |
| TC-CLI-02 | `run "task" --model gpt-4o` | --model 覆盖 |
| TC-CLI-03 | `run "status" --verbose --json` | 布尔标志 |
| TC-CLI-04 | `run` (无参数) | Missing required param |
| TC-CLI-05 | `run --help` | 帮助文本 |
| TC-CLI-06 | `echo "/exit" \| chat` | 交互式会话启动/退出 |
| TC-CLI-07 | `echo -e "say hi\n/exit" \| chat` | 自然语言 → AcquireGoalCmd |
| TC-CLI-08 | `tools` | 工具列表 |
| TC-CLI-09 | `tools --detail` | 工具详情 |
| TC-CLI-10 | `tools --help` | 帮助文本 |
| TC-CLI-11 | `config` | 配置概览 |
| TC-CLI-12 | `config get default.model` | 键值查询 |
| TC-CLI-13 | `config set openai.api_key sk-test-e2e` | 配置持久化 |
| TC-CLI-14 | `routine "0 */2 * * * ?"` | cron 解析 |
| TC-CLI-15 | `routine --list` | --list 标志 |
| TC-CLI-16 | `routine --help` | 帮助文本 |
| TC-CLI-17 | `sub-agent --spawn "monitor"` | SpawnSubAgentCmd |
| TC-CLI-18 | `sub-agent --list` | ListSubAgentsCmd |
| TC-CLI-19 | `sub-agent --connect --acp-endpoint` | ConnectSubAgentCmd |
| TC-CLI-20 | `sub-agent --cancel "id"` | CancelSubAgentCmd |
| TC-CLI-21 | `sub-agent --results "id"` | GetSubAgentResultsCmd |
| TC-CLI-22 | `sub-agent --send "id" --message` | SendToSubAgentCmd |
| TC-CLI-23 | `sub-agent --prompt "text" --agent-id` | PromptSubAgentCmd |
| TC-CLI-24 | `sub-agent` (空) | 空选项不崩溃 |
| TC-CLI-25 | `sub-agent --help` | 帮助文本 |

---

## 28. 测试实现状态

| 编号 | 测试文件 | 实现状态 | 说明 |
|------|---------|---------|------|
| TC-CLI-01~05 | `e2e/scene_cli_subcommands.py` | ✅ 通过 | PPAO 循环无 LLM 供应商时日志报错，但 exit=0，不影响 CLI 参数验证 |
| TC-CLI-06~07 | `e2e/scene_cli_subcommands.py` | ⏭️ 跳过 | TUI chat 使用 JLine 直接终端 I/O，stdout/stderr 无法通过 Gradle 子进程捕获。转由 `alice-facade-tui/e2e/` 测试 |
| TC-CLI-08~10 | `e2e/scene_cli_subcommands.py` | ✅ 通过 | |
| TC-CLI-11~13 | `e2e/scene_cli_subcommands.py` | ✅ 通过 | |
| TC-CLI-14~16 | `e2e/scene_cli_subcommands.py` | ✅ 通过 | |
| TC-CLI-17~25 | `e2e/scene_cli_subcommands.py` | ✅ 通过 | sub-agent 参数由 picocli 解析后存入 RunConfig，不走 PPAO dispatch；参数验证通过 RunConfig toString 实现 |

> **注意**：sub-agent 子命令当前通过 `run()` 主流程（picocli → RunConfig → ExecutionCoordinator），
> 其 picocli 参数解析正确性由 `CommandParserSpec` 和 `RunConfigSpec` 单元测试验证。
> sub-agent 的 `dispatchCommand()` 全路径分发见 `scene-dispatch-full-coverage.md`。

