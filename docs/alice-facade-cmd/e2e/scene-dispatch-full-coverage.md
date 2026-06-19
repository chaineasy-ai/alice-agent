---
title: "E2E Scene — dispatchCommand() Full Coverage"
summary: "验证 AliceCliLauncher.dispatchCommand() 对全部 21 种 AgentCommand 密封子类型的分发逻辑，确保每个分支都能正确处理或优雅拒绝。"
read_when:
  - "designing or implementing E2E tests for dispatchCommand()"
  - "verifying the AgentCommand switch pattern matching in AliceCliLauncher"
scope:
  - "alice-facade-cmd"
  - "docs/alice-facade-cmd/e2e/scene-dispatch-full-coverage.md"
status: "active"
updated: "2026-06-19"
---

# E2E Scene — dispatchCommand() Full Coverage

## 1. 场景描述

验证 `AliceCliLauncher.dispatchCommand(AgentCommand)` 方法对 **所有 21 种** AgentCommand 密封子类型的 switch 模式匹配分发逻辑。每个 AgentCommand 类型被分发后应返回正确的退出码，日志中体现处理结果或明确的拒绝信息。

### 1.1 输入路径

```
AgentCommand 实例 (由 AgentCommand.parse() 或 picocli 构建)
  → AliceCliLauncher.dispatchCommand(cmd)
  → switch(cmd) { case AcquireGoalCmd g -> ...; case ExecuteRawCmd e -> ...; ... }
  → 返回 int 退出码
```

### 1.2 覆盖指令（21 种密封子类型）

| 序号 | AgentCommand 类型 | 分支 | TC 编号 |
|------|------------------|------|---------|
| 01 | `AcquireGoalCmd` | ExecutionCmd | TC-DISPATCH-01 |
| 02 | `ExecuteRawCmd` | ExecutionCmd | TC-DISPATCH-02 |
| 03 | `RegisterSkillCmd` | CapabilityCmd | TC-DISPATCH-03 |
| 04 | `UpdateRulesCmd` | CapabilityCmd | TC-DISPATCH-04 |
| 05 | `ReloadKernelCmd` | CapabilityCmd | TC-DISPATCH-05 |
| 06 | `SwitchModelCmd` | AlignmentCmd | TC-DISPATCH-06 |
| 07 | `ResetSessionCmd` | ControlCmd | TC-DISPATCH-07 |
| 08 | `FeedbackCmd` | ControlCmd | TC-DISPATCH-08 |
| 09 | `InterruptCmd` | ControlCmd | TC-DISPATCH-09 |
| 10 | `ClearContextCmd` | ControlCmd | TC-DISPATCH-10 |
| 11 | `ViewContextCmd` | ControlCmd | TC-DISPATCH-11 |
| 12 | `CompactContextCmd` | ControlCmd | TC-DISPATCH-12 |
| 13 | `RegisterRoutineCmd` | RoutineTimeCmd | TC-DISPATCH-13 |
| 14 | `TimeTriggeredCmd` | RoutineTimeCmd | TC-DISPATCH-14 |
| 15 | `SpawnSubAgentCmd` | SubAgentCmd | TC-DISPATCH-15 |
| 16 | `ConnectSubAgentCmd` | SubAgentCmd | TC-DISPATCH-16 |
| 17 | `ListSubAgentsCmd` | SubAgentCmd | TC-DISPATCH-17 |
| 18 | `CancelSubAgentCmd` | SubAgentCmd | TC-DISPATCH-18 |
| 19 | `GetSubAgentResultsCmd` | SubAgentCmd | TC-DISPATCH-19 |
| 20 | `SendToSubAgentCmd` | SubAgentCmd | TC-DISPATCH-20 |
| 21 | `PromptSubAgentCmd` | SubAgentCmd | TC-DISPATCH-21 |

### 1.3 不覆盖

- CLI 子命令的 picocli 参数解析（见 [scene-cli-subcommands.md](./scene-cli-subcommands.md)）
- TUI 斜杠命令的 `AgentCommand.parse()` 映射（见 `docs/alice-facade-tui/e2e/scene-tui-slash-commands.md`）

---

## 2. 通用验证规则

每个 `dispatchCommand()` 测试验证以下三点：

1. **不抛出未捕获异常** — 进程正常退出（exit 0/1/2）
2. **返回适当退出码** — 已实现的功能返回 0，未实现的返回 1 或 2
3. **日志体现处理结果** — stdout/stderr 中包含该命令的处理日志

```python
def _verify_dispatch(self, input_args: list[str], expected_exit_codes: list[int],
                     expected_log: str, timeout: int = 60):
    result = run_cli(input_args, timeout=timeout)
    self.assertIn(result.returncode, expected_exit_codes,
                  msg=f"exit={result.returncode}: {result.stderr[:300]}")
    output = result.stdout + result.stderr
    self.assertIn(expected_log, output,
                  f"Expected '{expected_log}' in output")
```

---

## 3. TC-DISPATCH-01: AcquireGoalCmd

| 字段 | 值 |
|------|-----|
| **触发方式** | `alice run "test dispatch"` |
| **预期退出码** | 0 |
| **预期日志** | 包含 `dispatch` 或 `AcquireGoalCmd` 或 `RunConfig` |
| **说明** | picocli → RunConfig → ExecutionCoordinator 完整路径 |

---

## 4. TC-DISPATCH-02: ExecuteRawCmd

| 字段 | 值 |
|------|-----|
| **触发方式** | chat 管道: `echo -e "/exec echo hello\n/exit" \| alice chat` |
| **预期退出码** | 0 |
| **预期日志** | 包含 `ExecuteRawCmd`, `command: echo hello` |
| **说明** | ExecuteRawCmd 由 chat 模式中的 `/exec` 触发 |

---

## 5. TC-DISPATCH-03: RegisterSkillCmd

| 字段 | 值 |
|------|-----|
| **触发方式** | chat 管道: `echo -e "/skill mcp-tools.json\n/exit" \| alice chat` |
| **预期退出码** | 0 |
| **预期日志** | 包含 `RegisterSkillCmd`, `skillRef: mcp-tools.json` |
| **说明** | RegisterSkillCmd 由 chat 模式中的 `/skill` 触发 |

---

## 6. TC-DISPATCH-04: UpdateRulesCmd

| 字段 | 值 |
|------|-----|
| **触发方式** | chat 管道: `echo -e "/rules rules/my.prompt\n/exit" \| alice chat` |
| **预期退出码** | 0 |
| **预期日志** | 包含 `UpdateRulesCmd`, `rulesRef: rules/my.prompt` |
| **说明** | UpdateRulesCmd 由 chat 模式中的 `/rules` 触发 |

---

## 7. TC-DISPATCH-05: ReloadKernelCmd

| 字段 | 值 |
|------|-----|
| **触发方式** | chat 管道: `echo -e "/reload\n/exit" \| alice chat` |
| **预期退出码** | 0 |
| **预期日志** | 包含 `ReloadKernelCmd`, `resource: *` |
| **说明** | ReloadKernelCmd 由 chat 模式中的 `/reload` 触发 |

---

## 8. TC-DISPATCH-06: SwitchModelCmd

| 字段 | 值 |
|------|-----|
| **触发方式** | chat 管道: `echo -e "/model gpt-4o\n/exit" \| alice chat` |
| **预期退出码** | 0 |
| **预期日志** | 包含 `SwitchModelCmd`, `modelId: gpt-4o` |
| **说明** | SwitchModelCmd 由 chat 模式中的 `/model` 触发 |

---

## 9. TC-DISPATCH-07: ResetSessionCmd

| 字段 | 值 |
|------|-----|
| **触发方式** | chat 管道: `echo -e "/new\n/exit" \| alice chat` |
| **预期退出码** | 0 |
| **预期日志** | 包含 `ResetSessionCmd`, `reason: reset-session` |
| **说明** | ResetSessionCmd 由 chat 模式中的 `/new` 触发 |

---

## 10. TC-DISPATCH-08: FeedbackCmd

| 字段 | 值 |
|------|-----|
| **触发方式** | chat 管道: `echo -e "/feedback good\n/exit" \| alice chat` |
| **预期退出码** | 0 |
| **预期日志** | 包含 `FeedbackCmd`, `human-feedback` |
| **说明** | FeedbackCmd 由 chat 模式中的 `/feedback` 触发 |

---

## 11. TC-DISPATCH-09: InterruptCmd

| 字段 | 值 |
|------|-----|
| **触发方式** | chat 管道: `echo -e "/exit\n/exit" \| alice chat` |
| **预期退出码** | 0 |
| **预期日志** | 包含 `InterruptCmd`, `user-exit` |
| **说明** | InterruptCmd 由 chat 模式中的 `/exit` 触发 |

---

## 12. TC-DISPATCH-10: ClearContextCmd

| 字段 | 值 |
|------|-----|
| **触发方式** | chat 管道: `echo -e "/clear\n/exit" \| alice chat` |
| **预期退出码** | 0 |
| **预期日志** | 包含 `ClearContextCmd`, `clear-context` |
| **说明** | ClearContextCmd 由 chat 模式中的 `/clear` 触发 |

---

## 13. TC-DISPATCH-11: ViewContextCmd

| 字段 | 值 |
|------|-----|
| **触发方式** | chat 管道: `echo -e "/context\n/exit" \| alice chat` |
| **预期退出码** | 0 |
| **预期日志** | 包含 `ViewContextCmd`, `view-context` |
| **说明** | ViewContextCmd 由 chat 模式中的 `/context` 触发 |

---

## 14. TC-DISPATCH-12: CompactContextCmd

| 字段 | 值 |
|------|-----|
| **触发方式** | chat 管道: `echo -e "/compact\n/exit" \| alice chat` |
| **预期退出码** | 0 |
| **预期日志** | 包含 `CompactContextCmd`, `compact-context` |
| **说明** | CompactContextCmd 由 chat 模式中的 `/compact` 触发 |

---

## 15. TC-DISPATCH-13: RegisterRoutineCmd

| 字段 | 值 |
|------|-----|
| **触发方式** | `alice routine "0 */2 * * * ?"` |
| **预期退出码** | 0 |
| **预期日志** | 包含 `RegisterRoutineCmd` 或 `routineCron` |
| **说明** | RegisterRoutineCmd 由 CLI 的 `routine` 子命令触发 |

---

## 16. TC-DISPATCH-14: TimeTriggeredCmd

| 字段 | 值 |
|------|-----|
| **触发方式** | 程序化构造（内核 CronScheduler） |
| **预期退出码** | 不适用（内核内部触发，不走 CLI 路径） |
| **预期日志** | 不适用 |
| **说明** | TimeTriggeredCmd 不出现在 CLI/TUI 输入中，仅由内核构建 |
| **测试方法** | 跳过，或通过单元测试验证 `dispatchCommand()` 能匹配该类型 |

---

## 17. TC-DISPATCH-15: SpawnSubAgentCmd

| 字段 | 值 |
|------|-----|
| **触发方式** | `alice sub-agent --spawn "monitor disk"` |
| **预期退出码** | 0 |
| **预期日志** | 包含 `SpawnSubAgentCmd` 或 `subAgentSpawnGoal: monitor disk` |
| **说明** | SpawnSubAgentCmd 由 CLI 的 `sub-agent --spawn` 触发 |

---

## 18. TC-DISPATCH-16: ConnectSubAgentCmd

| 字段 | 值 |
|------|-----|
| **触发方式** | `alice sub-agent --connect "worker1" --acp-endpoint "http://x"` |
| **预期退出码** | 0 |
| **预期日志** | 包含 `ConnectSubAgentCmd` 或 `subAgentConnectName: worker1` |
| **说明** | ConnectSubAgentCmd 由 CLI 的 `sub-agent --connect` 触发 |

---

## 19. TC-DISPATCH-17: ListSubAgentsCmd

| 字段 | 值 |
|------|-----|
| **触发方式** | `alice sub-agent --list` |
| **预期退出码** | 0 |
| **预期日志** | 包含 `ListSubAgentsCmd` 或 `subAgentList: true` |
| **说明** | ListSubAgentsCmd 由 CLI 的 `sub-agent --list` 触发 |

---

## 20. TC-DISPATCH-18: CancelSubAgentCmd

| 字段 | 值 |
|------|-----|
| **触发方式** | `alice sub-agent --cancel "abc-123"` |
| **预期退出码** | 0 |
| **预期日志** | 包含 `CancelSubAgentCmd` 或 `subAgentCancelId: abc-123` |
| **说明** | CancelSubAgentCmd 由 CLI 的 `sub-agent --cancel` 触发 |

---

## 21. TC-DISPATCH-19: GetSubAgentResultsCmd

| 字段 | 值 |
|------|-----|
| **触发方式** | `alice sub-agent --results "abc-123"` |
| **预期退出码** | 0 |
| **预期日志** | 包含 `GetSubAgentResultsCmd` 或 `subAgentResultsId: abc-123` |
| **说明** | GetSubAgentResultsCmd 由 CLI 的 `sub-agent --results` 触发 |

---

## 22. TC-DISPATCH-20: SendToSubAgentCmd

| 字段 | 值 |
|------|-----|
| **触发方式** | `alice sub-agent --send "agent1" --message "hello"` |
| **预期退出码** | 0 |
| **预期日志** | 包含 `SendToSubAgentCmd` 或 `subAgentSendId: agent1` |
| **说明** | SendToSubAgentCmd 由 CLI 的 `sub-agent --send` 触发 |

---

## 23. TC-DISPATCH-21: PromptSubAgentCmd

| 字段 | 值 |
|------|-----|
| **触发方式** | `alice sub-agent --prompt "analyze" --agent-id "ext-agent"` |
| **预期退出码** | 0 |
| **预期日志** | 包含 `PromptSubAgentCmd` 或 `subAgentPromptAgentId: ext-agent` |
| **说明** | PromptSubAgentCmd 由 CLI 的 `sub-agent --prompt` 触发 |

---

## 24. AgentCommand 类型触发方式对照

| 类型 | CLI 触发 | TUI 触发 | 测试方法 |
|------|---------|---------|---------|
| AcquireGoalCmd | `run <task>` | `/run <goal>` / 自然语言 | CLI + chat 管道 |
| ExecuteRawCmd | — | `/exec <cmd>` | chat 管道 |
| RegisterSkillCmd | `tools`（间接） | `/skill <ref>` | CLI + chat 管道 |
| UpdateRulesCmd | — | `/rules <ref>` | chat 管道 |
| ReloadKernelCmd | — | `/reload` | chat 管道 |
| SwitchModelCmd | `run --model <id>` | `/model <id>` | CLI + chat 管道 |
| ResetSessionCmd | `chat` 入口 | `/new` | CLI + chat 管道 |
| FeedbackCmd | — | `/feedback <msg>` | chat 管道 |
| InterruptCmd | — | `/exit` | chat 管道 |
| ClearContextCmd | — | `/clear` | chat 管道 |
| ViewContextCmd | — | `/context` | chat 管道 |
| CompactContextCmd | — | `/compact` | chat 管道 |
| RegisterRoutineCmd | `routine <cron>` | `/routine <cron>` | CLI |
| TimeTriggeredCmd | — | — | 单元测试 |
| SpawnSubAgentCmd | `sub-agent --spawn` | `/sub-agent spawn` | CLI |
| ConnectSubAgentCmd | `sub-agent --connect` | `/sub-agent connect` | CLI |
| ListSubAgentsCmd | `sub-agent --list` | `/sub-agent list` | CLI |
| CancelSubAgentCmd | `sub-agent --cancel` | `/sub-agent cancel` | CLI |
| GetSubAgentResultsCmd | `sub-agent --results` | `/sub-agent results` | CLI |
| SendToSubAgentCmd | `sub-agent --send --message` | `/sub-agent send` | CLI |
| PromptSubAgentCmd | `sub-agent --prompt --agent-id` | `/sub-agent prompt` | CLI |

---

## 25. 测试用例汇总

| 编号 | dispatch 类型 | 触发入口 |
|------|-------------|---------|
| TC-DISPATCH-01 | AcquireGoalCmd | `run` CLI |
| TC-DISPATCH-02 | ExecuteRawCmd | chat `/exec` |
| TC-DISPATCH-03 | RegisterSkillCmd | chat `/skill` |
| TC-DISPATCH-04 | UpdateRulesCmd | chat `/rules` |
| TC-DISPATCH-05 | ReloadKernelCmd | chat `/reload` |
| TC-DISPATCH-06 | SwitchModelCmd | chat `/model` |
| TC-DISPATCH-07 | ResetSessionCmd | chat `/new` |
| TC-DISPATCH-08 | FeedbackCmd | chat `/feedback` |
| TC-DISPATCH-09 | InterruptCmd | chat `/exit` |
| TC-DISPATCH-10 | ClearContextCmd | chat `/clear` |
| TC-DISPATCH-11 | ViewContextCmd | chat `/context` |
| TC-DISPATCH-12 | CompactContextCmd | chat `/compact` |
| TC-DISPATCH-13 | RegisterRoutineCmd | `routine` CLI |
| TC-DISPATCH-14 | TimeTriggeredCmd | 内核（跳过） |
| TC-DISPATCH-15 | SpawnSubAgentCmd | `sub-agent --spawn` CLI |
| TC-DISPATCH-16 | ConnectSubAgentCmd | `sub-agent --connect` CLI |
| TC-DISPATCH-17 | ListSubAgentsCmd | `sub-agent --list` CLI |
| TC-DISPATCH-18 | CancelSubAgentCmd | `sub-agent --cancel` CLI |
| TC-DISPATCH-19 | GetSubAgentResultsCmd | `sub-agent --results` CLI |
| TC-DISPATCH-20 | SendToSubAgentCmd | `sub-agent --send --message` CLI |
| TC-DISPATCH-21 | PromptSubAgentCmd | `sub-agent --prompt --agent-id` CLI |

---

## 26. 测试实现状态

| TC 编号 | dispatch 类型 | 实现状态 | E2E 验证方式 | 说明 |
|---------|-------------|---------|-------------|------|
| TC-DISPATCH-01 | AcquireGoalCmd | ✅ 通过 | `alice run <task>` | 同 scene-cli-subcommands TC-CLI-01 |
| TC-DISPATCH-02~12 | ExecuteRawCmd ~ CompactContextCmd | ⏭️ 跳过 | chat 模式 (JLine) | 这些类型仅通过 chat 模式的 `/exec`, `/skill`, `/rules`, `/reload`, `/model`, `/new`, `/feedback`, `/exit`, `/clear`, `/context`, `/compact` 触发。JLine 终端 I/O 无法通过 Gradle 子进程捕获。dispatch 模式匹配由 `AliceCliLauncherDispatchSpec.groovy` 单元测试验证。 |
| TC-DISPATCH-13 | RegisterRoutineCmd | ✅ 通过 | `alice routine <cron>` | 同 scene-cli-subcommands TC-CLI-14 |
| TC-DISPATCH-14 | TimeTriggeredCmd | ⏭️ 跳过 | 内核内部 | 仅由 CronScheduler 构建，不暴露 CLI/TUI。由 RoutineTimeCmdParseSpec 单元测试验证。 |
| TC-DISPATCH-15 | SpawnSubAgentCmd | ✅ 通过 | `alice sub-agent --spawn` | 同 scene-cli-subcommands TC-CLI-17 |
| TC-DISPATCH-16 | ConnectSubAgentCmd | ✅ 通过 | `alice sub-agent --connect` | 同 scene-cli-subcommands TC-CLI-19 |
| TC-DISPATCH-17 | ListSubAgentsCmd | ✅ 通过 | `alice sub-agent --list` | 同 scene-cli-subcommands TC-CLI-18 |
| TC-DISPATCH-18 | CancelSubAgentCmd | ✅ 通过 | `alice sub-agent --cancel` | 同 scene-cli-subcommands TC-CLI-20 |
| TC-DISPATCH-19~21 | GetSubAgentResultsCmd ~ PromptSubAgentCmd | ✅ 通过 | `alice sub-agent --results/send/prompt` | 同 scene-cli-subcommands TC-CLI-21~23 |

> **注意**：chat 模式下的 dispatch 路径经由 `JLineChatSession.run()` → `dispatchAndRender(cmd)` → `AliceCliLauncher.dispatchCommand(cmd)`。JLine 的 `TerminalBuilder.builder().system(true).build()` 在非交互环境（管道/子进程）中无法打开终端，因此 chat 相关类型的 E2E 测试需要人工交互环境或 JUnit 单元测试。

## 27. 单元测试交叉引用

| E2E 跳过的类型 | 对应单元测试文件 |
|---------------|----------------|
| ExecuteRawCmd | `AgentCommandParseSpec.groovy` — `/exec` 解析测试 |
| RegisterSkillCmd | `CapabilityCmdSpec.groovy` — RegisterSkillCmd 验证 |
| UpdateRulesCmd | `CapabilityCmdSpec.groovy` — UpdateRulesCmd 验证 |
| ReloadKernelCmd | `CapabilityCmdSpec.groovy` — ReloadKernelCmd 验证 |
| SwitchModelCmd | `AlignmentCmdSpec.groovy` — SwitchModelCmd 验证 |
| ResetSessionCmd | `ControlCmdSpec.groovy` — ResetSessionCmd 验证 |
| FeedbackCmd | `ControlCmdSpec.groovy` — FeedbackCmd 验证 |
| InterruptCmd | `ControlCmdSpec.groovy` — InterruptCmd 验证 |
| ClearContextCmd | `ControlCmdSpec.groovy` — ClearContextCmd 验证 |
| ViewContextCmd | `ControlCmdSpec.groovy` — ViewContextCmd 验证 |
| CompactContextCmd | `ControlCmdSpec.groovy` — CompactContextCmd 验证 |
| TimeTriggeredCmd | `RoutineTimeCmdSpec.groovy`, `RoutineTimeCmdParseSpec.groovy` |
| 全部 SubAgentCmd 子类型 | `SubAgentCmdSpec.groovy`, `SubAgentCmdParseSpec.groovy`, `AgentCommandSealedHierarchySpec.groovy` |
