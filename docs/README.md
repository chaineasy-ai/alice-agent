---
title: "docs - Documentation Directory"
summary: "Index and description of the docs/ directory"
read_when:
  - "indexing or understanding the docs directory structure"
scope:
  - "docs"
status: "active"
updated: "2026-09-19"
---
# docs - 文档目录

## 构建与工具

| 文档 | 说明 |
|------|------|
| [jcoco.md](./jcoco.md) | JaCoCo 单元测试覆盖率配置（Gradle 多模块） |
| [mapstruct.md](./mapstruct.md) | MapStruct 对象映射框架集成配置与使用指南 |

## 规范

| 文档 | 说明 |
|------|------|
| [CHANGELOG规范.md](./CHANGELOG规范.md) | CHANGELOG 编写规范 |

## 架构

| 文档 | 说明 |
|------|------|
| [architecture/extension-layer.md](./architecture/extension-layer.md) | **扩展层设计（二期实施）**：能力端口 + 行为钩子、清单/生命周期/权限/失败隔离/版本协商；端口归 proto、注册表归 runtime |

## 模块设计（重点）

| 文档 | 说明 |
|------|------|
| [alice-agent-proto/DESIGN.md](./alice-agent-proto/DESIGN.md) | **协议契约层**：`AgentCommand` 命令（入）+ `StepEvent` 事件（出）+ `AgentCommandDispatcher` 端口 + codec/版本 |
| [alice-agent-proto/PROTOCOL.md](./alice-agent-proto/PROTOCOL.md) | **协议 v1 帧规范**：分帧规则 / 命令信封 / StepEvent 字段与语义 / 错误映射 / 兼容纪律 / pi 对接映射 |
| [alice-agent-runtime/README.md](../alice-agent-runtime/README.md) | **会话宿主**：一轮一锁（原子 CAS）/ 事件映射 / 收口帧 / 健康快照 + `InProcessTransport` |
| [alice-facade-rpc/DESIGN.md](./alice-facade-rpc/DESIGN.md) | **HTTP RPC 2.0**（协议适配层）：6 路由 + SSE + 错误映射；只依赖 proto/runtime |
| [alice-facade-rpc/README.md](../alice-facade-rpc/README.md) | 模块使用说明（路由表 / 用法 / 实现说明 / 测试） |
| [alice-facade-cli/DESIGN.md](./alice-facade-cli/DESIGN.md) | UI 适配层（面向人）：CLI/picocli + JLine REPL；`--json` 帧统一走 proto codec |
| [alice-facade-tui/DESIGN.md](./alice-facade-tui/DESIGN.md) | UI 适配层（面向人）：JLine TUI 布局与事件桥接 |

## Agent 通信 / 对接

| 文档 | 说明 |
|------|------|
| [pi-integration/README.md](./pi-integration/README.md) | **Pi 通信对接协议总览**：三层通信（stdio RPC / 常驻 worker HTTP / 调度唤醒+插话）、会话与生命周期约定、观测埋点口径、alice-agent 落地清单、踩坑清单 |
| [pi-integration/rpc-stdio.md](./pi-integration/rpc-stdio.md) | 层① stdio RPC（JSONL）：启动参数、与 framing 规则、命令/事件帧、轮结束判定（`agent_settled`）与 usage 累计 |
| [pi-integration/worker-http.md](./pi-integration/worker-http.md) | 层② 常驻 worker HTTP API：`/health` `/prompt` `/steer` `/abort` `/new_session` `/shutdown` 契约、轮次并发语义、客户端行为与 curl 验收 |

## 管理

| 文档 | 说明 |
|------|------|
| [管理/接管与路线图-20261001.md](./管理/接管与路线图-20261001.md) | **接管建档（#173）**：T1 构建测试基线 + CI 红根因、T2 资产盘点（模块/docs/specs/todos/e2e）、T3 M1–M3 路线图与出口判据、T4 M1 第一切片（#174 取消语义）、T5 治理建议 |

## 发布 / 工作清单

| 文档 | 说明 |
|------|------|
| [release/20260919/协议层转正与门面收敛-清单.md](./release/20260919/协议层转正与门面收敛-清单.md) | 2026-09-19 发布清单：协议契约层转正（`alice-agent-proto`）、宿主抽取（会话语义）、门面收敛（web → HTTP RPC 2.0）；含前置决策、验收门槛与暂缓项 |
