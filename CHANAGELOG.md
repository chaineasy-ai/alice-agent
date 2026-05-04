---
description: record your changes
---

# Changelog

## Unreleased

### Changes

- alice-model/领域模型：新增 `Call`、`CallStatus`、`Model`、`ModelSupplier` 核心领域对象，对齐设计文档(`DESIGN.md`) 架构。(#1)
- alice-model/枚举：新增 14 个内置模型枚举定义（GPT-4o、Claude、Gemini、DeepSeek、Qwen 系列），含能力标签与成本定价。
- alice-model/供应商：新增 `OpenAiSupplier` 实现 `ModelSupplier` 接口，支持 OpenAI Chat Completion API 调用。
- alice-model/路由：`ModelProvider` 实现完整调度链路：Supplier 注册、Model 注册、路由策略、`dispatch()` 执行。
- alice-core-agent/Agent：实现 `ask(prompt)` / `ask(prompt, modelId)` 方法，集成 `ModelProvider` 调用链路。
- build/配置：在 `alice-core-agent` 和 `app` 模块中添加 `alice-model` 子模块依赖。
- build/测试：修复 JUnit Platform Launcher 缺失问题，确保 Spock 测试正常执行。
- docs: 新增 `docs/alice-model/README.md`，更新模块文档与架构描述。
- docs: 更新 README.md，用 `project.tree` 替代内联项目结构；新增 `project.tree` 文件。
- alice-core-agent/PPAO 闭环：实现 `AgentCore`、`AgentExecutor`、`AgentConfig`，基于 Vert.x 响应式 Future 链驱动循环：Perceive → Plan → Verify(Pre) → Act → Observe → Verify(Post) → Reflect → (loop|finish)。
- alice-core-agent/阶段状态机：`AgentContext` 新增 `Phase` 枚举（START→PERCEIVING→PLANNING→VERIFYING_PRE→ACTING→OBSERVING→VERIFYING_POST→REFLECTING→REVISION→FINISH），含合法转换校验。
- alice-core-agent/密封结果类型：新增 `StepResult` 密封类，模式匹配 `Continue(Action)` / `Finish(answer)` / `Failure(error)`。
- alice-core-agent/动作模型：新增 `Action`（TOOL_CALL/LLM_INFERENCE/FINISH/REVISION/WAIT/OBSERVE）与 `Observation`（SUCCESS/FAILURE/PARTIAL/TIMEOUT/BLOCKED）。
- alice-core-agent/生命周期接口：新增 `Lifecycle<I>` 接口定义 PPAO 各阶段契约。
- alice-core-planner/ReAct：新增 `proposeNext(Map)` 方法，通过 Map 接口与 core-agent 解耦。
- alice-guardrail/Verificator：新增 `intercept(Map)` / `audit(Object)` 默认方法，通过 Map/Object 与 core-agent 解耦。
- alice-tool-gateway/ToolRegistry：新增 `register()` / `execute()` / `hasTool()` 等方法，支持工具注册与调用。
- alice-memory-vault/AgentSession：新增 `persist()` / `getShortTerm()` / `putLongTerm()` 等记忆存取方法。
- alice-core-agent/测试：新增 5 个 Spock 测试（AgentContextSpec / ActionSpec / StepResultSpec / ObservationSpec / AgentConfigSpec），共 17 个测试用例全部通过。
- build/依赖：所有子模块构建与编译通过，消除模块间的循环依赖（core-planner / guardrail 通过 Map 接口与 core-agent 交互）。

### Fixes

- alice-model/模块系统：修复 `module-info.java` 中 facade 导出包为空的问题，移除冗余 facade 层。

## 20260503

### Changes

- 项目初始化：创建多模块 Java 25 + Gradle 9.5 项目骨架，含 8 个子模块。
