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

### Fixes

- alice-model/模块系统：修复 `module-info.java` 中 facade 导出包为空的问题，移除冗余 facade 层。

## 20260503

### Changes

- 项目初始化：创建多模块 Java 25 + Gradle 9.5 项目骨架，含 8 个子模块。
