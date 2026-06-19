---
title: "E2E Scene — TUI Slash Commands"
summary: "验证 AgentCommand.parse() 对 20 种 TUI 斜杠命令 + 自然语言的解析，模拟交互式 chat 模式的管道输入场景。对应 AgentCommand 密封指令的 TUI 输入路径。属于 alice-facade-tui 模块的 E2E 场景。"
read_when:
  - "designing or implementing E2E tests for TUI slash commands"
  - "verifying AgentCommand.parse() mapping for /run, /exec, /skill, /rules, /reload, /model, /new, /feedback, /exit, /clear, /context, /compact, /routine, /sub-agent"
scope:
  - "alice-facade-tui"
  - "docs/alice-facade-tui/e2e/scene-tui-slash-commands.md"
status: "active"
updated: "2026-06-19"
---

# E2E Scene — TUI Slash Commands

## 1. 场景描述

验证通过 `alice chat` 交互式会话的管道输入模拟 TUI 场景时，`AgentCommand.parse()` 能正确地将 `/xxx` 斜杠命令和自然语言文本映射到对应的 AgentCommand 密封实例。

### 1.1 输入路径

```
echo -e "/run write a poem\n/exit" | alice chat
  → JLineChatSession.readLine()
  → AgentCommand.parse(input, sessionId, traceId)
  → AgentCommand 密封实例 (AcquireGoalCmd, ExecuteRawCmd, etc.)
  → AliceCliLauncher.dispatchCommand()
  → 执行 / 拒绝
```

### 1.2 覆盖指令（20 种 + 自然语言）

| 输入 | AgentCommand 类型 | 场景编号 |
|------|------------------|---------|
| 自然语言 `hello` | `AcquireGoalCmd` | TC-SLASH-01 |
| `/run <goal>` | `AcquireGoalCmd` | TC-SLASH-02~03 |
| `/exec <cmd>` | `ExecuteRawCmd` | TC-SLASH-04~05 |
| `/skill <ref>` | `RegisterSkillCmd` | TC-SLASH-06 |
| `/rules <ref>` | `UpdateRulesCmd` | TC-SLASH-07 |
| `/reload` | `ReloadKernelCmd` | TC-SLASH-08~09 |
| `/model <id>` | `SwitchModelCmd` | TC-SLASH-10~11 |
| `/new` | `ResetSessionCmd` | TC-SLASH-12 |
| `/feedback <msg>` | `FeedbackCmd` | TC-SLASH-13 |
| `/exit` | `InterruptCmd` | TC-SLASH-14 |
| `/clear` | `ClearContextCmd` | TC-SLASH-15~16 |
| `/context` | `ViewContextCmd` | TC-SLASH-17 |
| `/compact` | `CompactContextCmd` | TC-SLASH-18 |
| `/routine <cron>` | `RegisterRoutineCmd` | TC-SLASH-19~20 |
| `/sub-agent spawn` | `SpawnSubAgentCmd` | TC-SLASH-21~22 |
| `/sub-agent connect` | `ConnectSubAgentCmd` | TC-SLASH-23 |
| `/sub-agent list` | `ListSubAgentsCmd` | TC-SLASH-24 |
| `/sub-agent cancel` | `CancelSubAgentCmd` | TC-SLASH-25 |
| `/sub-agent results` | `GetSubAgentResultsCmd` | TC-SLASH-26 |
| `/sub-agent send` | `SendToSubAgentCmd` | TC-SLASH-27 |
| `/sub-agent prompt` | `PromptSubAgentCmd` | TC-SLASH-28 |
| `/unknown` | `null` | TC-SLASH-29 |
| 空行 | `null` | TC-SLASH-30 |

### 1.3 不覆盖

- CLI 子命令的 picocli 解析（见 [scene-cli-subcommands.md](./scene-cli-subcommands.md)）
- `dispatchCommand()` 内部 switch 实现细节（见 [scene-dispatch-full-coverage.md](./scene-dispatch-full-coverage.md)）

---

## 2. TC-SLASH-01: 自然语言 → AcquireGoalCmd

| 字段 | 值 |
|------|-----|
| **管道输入** | `echo -e "hello ai\n/exit" \| alice chat` |
| **预期退出码** | 0 |
| **预期日志** | 包含 `AcquireGoalCmd`, `goal: hello ai` |
| **验证点** | 非斜杠输入自动回退为 AcquireGoalCmd |

---

## 3. TC-SLASH-02: `/run` → AcquireGoalCmd

| 字段 | 值 |
|------|-----|
| **管道输入** | `echo -e "/run write a poem\n/exit" \| alice chat` |
| **预期退出码** | 0 |
| **预期日志** | 包含 `AcquireGoalCmd`, `goal: write a poem` |
| **验证点** | `/run <text>` 正确映射 |

---

## 4. TC-SLASH-03: `/run` 无参数

| 字段 | 值 |
|------|-----|
| **管道输入** | `echo -e "/run\n/exit" \| alice chat` |
| **预期退出码** | 0 |
| **预期日志** | 包含 `AcquireGoalCmd`, `(empty /run)` 占位 |
| **验证点** | `/run` 无参数时使用空字符串占位 |

---

## 5. TC-SLASH-04: `/exec` → ExecuteRawCmd

| 字段 | 值 |
|------|-----|
| **管道输入** | `echo -e "/exec nvidia-smi\n/exit" \| alice chat` |
| **预期退出码** | 0 |
| **预期日志** | 包含 `ExecuteRawCmd`, `command: nvidia-smi` |
| **验证点** | `/exec <cmd>` 正确映射 |

---

## 6. TC-SLASH-05: `/exec` 无参数（默认回退）

| 字段 | 值 |
|------|-----|
| **管道输入** | `echo -e "/exec\n/exit" \| alice chat` |
| **预期退出码** | 0 |
| **预期日志** | 包含 `ExecuteRawCmd`, `echo`（默认命令） |
| **验证点** | `/exec` 无参数时使用默认回退命令 |

---

## 7. TC-SLASH-06: `/skill` → RegisterSkillCmd

| 字段 | 值 |
|------|-----|
| **管道输入** | `echo -e "/skill mcp-tools.json\n/exit" \| alice chat` |
| **预期退出码** | 0 |
| **预期日志** | 包含 `RegisterSkillCmd`, `skillRef: mcp-tools.json` |
| **验证点** | `/skill <ref>` 正确映射 |

---

## 8. TC-SLASH-07: `/rules` → UpdateRulesCmd

| 字段 | 值 |
|------|-----|
| **管道输入** | `echo -e "/rules rules/my.prompt\n/exit" \| alice chat` |
| **预期退出码** | 0 |
| **预期日志** | 包含 `UpdateRulesCmd`, `rulesRef: rules/my.prompt` |
| **验证点** | `/rules <ref>` 正确映射 |

---

## 9. TC-SLASH-08: `/reload` → ReloadKernelCmd

| 字段 | 值 |
|------|-----|
| **管道输入** | `echo -e "/reload\n/exit" \| alice chat` |
| **预期退出码** | 0 |
| **预期日志** | 包含 `ReloadKernelCmd`, `resource: *` |
| **验证点** | `/reload` 正确映射 |

---

## 10. TC-SLASH-09: `/reload` 带额外参数（应忽略）

| 字段 | 值 |
|------|-----|
| **管道输入** | `echo -e "/reload all\n/exit" \| alice chat` |
| **预期退出码** | 0 |
| **预期日志** | 包含 `ReloadKernelCmd`, `resource: *`（参数被忽略） |
| **验证点** | `/reload` 忽略额外参数 |

---

## 11. TC-SLASH-10: `/model` → SwitchModelCmd

| 字段 | 值 |
|------|-----|
| **管道输入** | `echo -e "/model claude-3.5\n/exit" \| alice chat` |
| **预期退出码** | 0 |
| **预期日志** | 包含 `SwitchModelCmd`, `modelId: claude-3.5` |
| **验证点** | `/model <id>` 正确映射 |

---

## 12. TC-SLASH-11: `/model` 无参数（默认模型）

| 字段 | 值 |
|------|-----|
| **管道输入** | `echo -e "/model\n/exit" \| alice chat` |
| **预期退出码** | 0 |
| **预期日志** | 包含 `SwitchModelCmd`, `modelId: gpt-4o` |
| **验证点** | `/model` 无参数时使用默认模型 |

---

## 13. TC-SLASH-12: `/new` → ResetSessionCmd

| 字段 | 值 |
|------|-----|
| **管道输入** | `echo -e "/new\n/exit" \| alice chat` |
| **预期退出码** | 0 |
| **预期日志** | 包含 `ResetSessionCmd`, `reason: reset-session` |
| **验证点** | `/new` 正确映射 |

---

## 14. TC-SLASH-13: `/feedback` → FeedbackCmd

| 字段 | 值 |
|------|-----|
| **管道输入** | `echo -e "/feedback 回答太长了\n/exit" \| alice chat` |
| **预期退出码** | 0 |
| **预期日志** | 包含 `FeedbackCmd`, `human-feedback`, `回答太长了` |
| **验证点** | `/feedback <msg>` 正确映射 |

---

## 15. TC-SLASH-14: `/exit` → InterruptCmd

| 字段 | 值 |
|------|-----|
| **管道输入** | `echo -e "/exit\n/exit" \| alice chat` |
| **预期退出码** | 0 |
| **预期日志** | 包含 `InterruptCmd`, `user-exit` |
| **验证点** | `/exit` 正确映射 |

---

## 16. TC-SLASH-15: `/clear` → ClearContextCmd

| 字段 | 值 |
|------|-----|
| **管道输入** | `echo -e "/clear\n/exit" \| alice chat` |
| **预期退出码** | 0 |
| **预期日志** | 包含 `ClearContextCmd`, `clear-context` |
| **验证点** | `/clear` 正确映射 |

---

## 17. TC-SLASH-16: `/clear` 带额外参数（应忽略）

| 字段 | 值 |
|------|-----|
| **管道输入** | `echo -e "/clear all\n/exit" \| alice chat` |
| **预期退出码** | 0 |
| **预期日志** | 包含 `ClearContextCmd`（参数被忽略） |
| **验证点** | `/clear` 忽略额外参数 |

---

## 18. TC-SLASH-17: `/context` → ViewContextCmd

| 字段 | 值 |
|------|-----|
| **管道输入** | `echo -e "/context\n/exit" \| alice chat` |
| **预期退出码** | 0 |
| **预期日志** | 包含 `ViewContextCmd`, `view-context` |
| **验证点** | `/context` 正确映射 |

---

## 19. TC-SLASH-18: `/compact` → CompactContextCmd

| 字段 | 值 |
|------|-----|
| **管道输入** | `echo -e "/compact\n/exit" \| alice chat` |
| **预期退出码** | 0 |
| **预期日志** | 包含 `CompactContextCmd`, `compact-context` |
| **验证点** | `/compact` 正确映射 |

---

## 20. TC-SLASH-19: `/routine` → RegisterRoutineCmd

| 字段 | 值 |
|------|-----|
| **管道输入** | `echo -e "/routine 0 */2 * * * ?\n/exit" \| alice chat` |
| **预期退出码** | 0 |
| **预期日志** | 包含 `RegisterRoutineCmd`, `cronExpression: 0 */2 * * * ?` |
| **验证点** | `/routine <cron>` 正确映射 |

---

## 21. TC-SLASH-20: `/routine` 无参数

| 字段 | 值 |
|------|-----|
| **管道输入** | `echo -e "/routine\n/exit" \| alice chat` |
| **预期退出码** | 0 |
| **预期日志** | 包含 `RegisterRoutineCmd`, cron 表达式为空 |
| **验证点** | `/routine` 无参数时 cron 为空字符串 |

---

## 22. TC-SLASH-21: `/sub-agent spawn` → SpawnSubAgentCmd

| 字段 | 值 |
|------|-----|
| **管道输入** | `echo -e '/sub-agent spawn --goal "analyze code"\n/exit' \| alice chat` |
| **预期退出码** | 0 |
| **预期日志** | 包含 `SpawnSubAgentCmd`, `goal: analyze code` |
| **验证点** | `/sub-agent spawn` 正确解析 --goal |

---

## 23. TC-SLASH-22: `/sub-agent spawn` with --model

| 字段 | 值 |
|------|-----|
| **管道输入** | `echo -e '/sub-agent spawn --goal "monitor" --model gpt-4o\n/exit' \| alice chat` |
| **预期退出码** | 0 |
| **预期日志** | 包含 `SpawnSubAgentCmd`, `model: gpt-4o` |
| **验证点** | spawn 的 --model 参数正确解析 |

---

## 24. TC-SLASH-23: `/sub-agent connect` → ConnectSubAgentCmd

| 字段 | 值 |
|------|-----|
| **管道输入** | `echo -e '/sub-agent connect --name "worker" --acp-endpoint http://x:9000\n/exit' \| alice chat` |
| **预期退出码** | 0 |
| **预期日志** | 包含 `ConnectSubAgentCmd`, `name: worker`, `endpoint: http://x:9000` |
| **验证点** | `/sub-agent connect` 完整解析 |

---

## 25. TC-SLASH-24: `/sub-agent list` → ListSubAgentsCmd

| 字段 | 值 |
|------|-----|
| **管道输入** | `echo -e "/sub-agent list\n/exit" \| alice chat` |
| **预期退出码** | 0 |
| **预期日志** | 包含 `ListSubAgentsCmd` |
| **验证点** | `/sub-agent list` 正确映射 |

---

## 26. TC-SLASH-25: `/sub-agent cancel` → CancelSubAgentCmd

| 字段 | 值 |
|------|-----|
| **管道输入** | `echo -e "/sub-agent cancel abc-123\n/exit" \| alice chat` |
| **预期退出码** | 0 |
| **预期日志** | 包含 `CancelSubAgentCmd`, `subAgentId: abc-123` |
| **验证点** | `/sub-agent cancel <id>` 正确解析 |

---

## 27. TC-SLASH-26: `/sub-agent results` → GetSubAgentResultsCmd

| 字段 | 值 |
|------|-----|
| **管道输入** | `echo -e "/sub-agent results abc-123\n/exit" \| alice chat` |
| **预期退出码** | 0 |
| **预期日志** | 包含 `GetSubAgentResultsCmd`, `subAgentId: abc-123` |
| **验证点** | `/sub-agent results <id>` 正确解析 |

---

## 28. TC-SLASH-27: `/sub-agent send` → SendToSubAgentCmd

| 字段 | 值 |
|------|-----|
| **管道输入** | `echo -e '/sub-agent send agent1 "report status"\n/exit' \| alice chat` |
| **预期退出码** | 0 |
| **预期日志** | 包含 `SendToSubAgentCmd`, `subAgentId: agent1`, `message: report status` |
| **验证点** | `/sub-agent send <id> <msg>` 正确解析 |

---

## 29. TC-SLASH-28: `/sub-agent prompt` → PromptSubAgentCmd

| 字段 | 值 |
|------|-----|
| **管道输入** | `echo -e '/sub-agent prompt ext-agent "analyze this"\n/exit' \| alice chat` |
| **预期退出码** | 0 |
| **预期日志** | 包含 `PromptSubAgentCmd`, `subAgentId: ext-agent`, `prompt: analyze this` |
| **验证点** | `/sub-agent prompt <id> <prompt>` 正确解析 |

---

## 30. TC-SLASH-29: 未知斜杠命令

| 字段 | 值 |
|------|-----|
| **管道输入** | `echo -e "/unknown\n/exit" \| alice chat` |
| **预期退出码** | 0 |
| **预期日志** | 未知命令不被分发（`parse()` 返回 `null`），不崩溃 |
| **验证点** | 未知斜杠命令被优雅拒绝 |

---

## 31. TC-SLASH-30: 空输入

| 字段 | 值 |
|------|-----|
| **管道输入** | `echo -e "\n/exit" \| alice chat` |
| **预期退出码** | 0 |
| **预期日志** | 空行被忽略，不崩溃 |
| **验证点** | 空输入不会引发异常 |

---

## 32. 测试用例汇总

| 编号 | 管道输入 | AgentCommand 类型 |
|------|---------|------------------|
| TC-SLASH-01 | `hello ai` | `AcquireGoalCmd` |
| TC-SLASH-02 | `/run write a poem` | `AcquireGoalCmd` |
| TC-SLASH-03 | `/run` | `AcquireGoalCmd` (empty) |
| TC-SLASH-04 | `/exec nvidia-smi` | `ExecuteRawCmd` |
| TC-SLASH-05 | `/exec` | `ExecuteRawCmd` (default) |
| TC-SLASH-06 | `/skill mcp-tools.json` | `RegisterSkillCmd` |
| TC-SLASH-07 | `/rules rules/my.prompt` | `UpdateRulesCmd` |
| TC-SLASH-08 | `/reload` | `ReloadKernelCmd` |
| TC-SLASH-09 | `/reload all` | `ReloadKernelCmd` (ignored args) |
| TC-SLASH-10 | `/model claude-3.5` | `SwitchModelCmd` |
| TC-SLASH-11 | `/model` | `SwitchModelCmd` (default) |
| TC-SLASH-12 | `/new` | `ResetSessionCmd` |
| TC-SLASH-13 | `/feedback 回答太长了` | `FeedbackCmd` |
| TC-SLASH-14 | `/exit` | `InterruptCmd` |
| TC-SLASH-15 | `/clear` | `ClearContextCmd` |
| TC-SLASH-16 | `/clear all` | `ClearContextCmd` (ignored args) |
| TC-SLASH-17 | `/context` | `ViewContextCmd` |
| TC-SLASH-18 | `/compact` | `CompactContextCmd` |
| TC-SLASH-19 | `/routine 0 */2 * * * ?` | `RegisterRoutineCmd` |
| TC-SLASH-20 | `/routine` | `RegisterRoutineCmd` (empty) |
| TC-SLASH-21 | `/sub-agent spawn --goal "analyze code"` | `SpawnSubAgentCmd` |
| TC-SLASH-22 | `/sub-agent spawn --goal "monitor" --model gpt-4o` | `SpawnSubAgentCmd` |
| TC-SLASH-23 | `/sub-agent connect --name "worker" --acp-endpoint http://x:9000` | `ConnectSubAgentCmd` |
| TC-SLASH-24 | `/sub-agent list` | `ListSubAgentsCmd` |
| TC-SLASH-25 | `/sub-agent cancel abc-123` | `CancelSubAgentCmd` |
| TC-SLASH-26 | `/sub-agent results abc-123` | `GetSubAgentResultsCmd` |
| TC-SLASH-27 | `/sub-agent send agent1 "report status"` | `SendToSubAgentCmd` |
| TC-SLASH-28 | `/sub-agent prompt ext-agent "analyze this"` | `PromptSubAgentCmd` |
| TC-SLASH-29 | `/unknown` | `null` |
| TC-SLASH-30 | 空行 | `null` |

---

## 33. 测试实现状态

| 编号 | AgentCommand 类型 | 实现状态 | 说明 |
|------|-----------------|---------|------|
| TC-SLASH-01~20 | AcquireGoalCmd ~ RegisterRoutineCmd | ⏭️ 跳过 | 所有 `/xxx` 斜杠命令均依赖 JLine 终端交互 (`JLineChatSession.run()`)，无法通过 Gradle 子进程管道捕获。模式匹配映射由 `AgentCommandParseSpec.groovy` 单元测试覆盖。 |
| TC-SLASH-21~28 | SpawnSubAgentCmd ~ PromptSubAgentCmd | ⏭️ 跳过 | 同上，JLine 终端依赖。CLI 等效命令 (`alice sub-agent --spawn/--list/--cancel` 等) 已在 `scene-cli-subcommands.md` 中 E2E 验证。 |
| TC-SLASH-29~30 | 未知命令 / 空输入 | ⏭️ 跳过 | JLine 终端依赖。`AgentCommand.parse()` 返回 `null` 逻辑由 `AgentCommandParseSpec.groovy` 覆盖。 |

> **技术原因**：`JLineChatSession` 使用 `TerminalBuilder.builder().system(true).build()` 打开系统终端，在非交互式环境（stdin 管道 / Gradle 子进程）中无法完成初始化。`dispatchCommand(String input)` 虽然是 `public static`，但仅由 `JLineChatSession.dispatchAndRender()` 调用，不直接暴露在 CLI `run()` 路径中。

## 34. 单元测试交叉引用

| 场景 | 单元测试文件 | 验证内容 |
|------|-------------|---------|
| 全部斜杠命令映射 | `AgentCommandParseSpec.groovy` | 每个 `/xxx` 到对应 AgentCommand 密封子类型的映射 |
| SubAgentCmd 子命令解析 | `SubAgentCmdParseSpec.groovy` | `/sub-agent spawn/connect/list/cancel/results/send/prompt` 解析 |
| SubAgentCmd 密封层次 | `SubAgentCmdSealedHierarchySpec.groovy` | 全部 7 种 SubAgentCmd 子类型密封性 |
| 全部 21 种密封子类型 | `AgentCommandSealedHierarchySpec.groovy` | AgentCommand 完整密封层次 |
| CLI 等效命令 E2E | `scene-cli-subcommands.md` / `scene_cli_subcommands.py` | `alice sub-agent --spawn/--list/--cancel/--results/--send/--prompt` |
| dispatch 全路径 E2E | `scene-dispatch-full-coverage.md` | CLI 可达的 dispatch 分支验证 |
