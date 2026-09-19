---
title: "扩展层设计（Extension Layer）"
summary: "横切架构层：扩展点（能力端口 + 行为钩子）、清单/生命周期/权限/失败隔离/版本协商；端口归属 proto、注册表归属 runtime、实现可第一方或第三方"
read_when:
  - "设计或实现扩展点（工具/模型/记忆/守卫/传输/钩子）"
  - "接入第三方能力或做插件化"
  - "排查扩展装载/冲突/失败隔离问题"
scope:
  - "alice-agent-proto"
  - "alice-agent-runtime"
  - "alice-core-agent"
status: "planned"
updated: "2026-09-19"
---
# 扩展层设计（Extension Layer）

> **实施排期：二期（Phase 2）· 本期仅设计**。本期只落契约方向（proto §9：端口/钩子/清单 schema），
> 不实施；开放问题 Q1-Q4 二期拍板后开工（见 §6）。立项理由：**能力/行为增长不应靠改内核**，
> 对项目后续迭代关键，因此先把契约与治理规则沉淀成文档。

> **2026-09-19 评审补充**：除「契约 / 实现 / 宿主 / 传输 / 呈现」外，工程架构上还缺一个**扩展层**——
> 让能力与行为**不重编译内核**即可增删（第一方与第三方走同一条路）。
> 参考模型：pi 的 extensions（`pi.on(...)` 事件族 + `registerTool/registerCommand` + 目录自动发现 + 热重载）。

---

## 1. 定位：横切层，不是第 6 个核心模块

```
契约层 alice-agent-proto    ← 端口 / 清单 schema / 扩展事件类型（扩展只依赖它）
        ▲
扩展层（本设计）              ← 发现/装载/生命周期/权限/隔离/钩子调度 → 落在 alice-agent-runtime 子系统
        ▲
实现层 alice-core-agent      ← 只认端口（DIP），不认识任何具体扩展
宿主 / 传输 / 呈现 …
```

| 组成 | 归属 | 说明 |
|---|---|---|
| 端口 + 清单 schema + 扩展事件 | `alice-agent-proto`（契约） | 扩展只准依赖契约 |
| 注册表 / 生命周期 / 权限 / 隔离 / hook 调度 | `alice-agent-runtime`（宿主子系统） | 不新增核心模块 |
| 具体扩展 | 第一方模块（tool-gateway / model / memory-vault / guardrail / env-adapter）或**外部包**（SPI/目录发现） | 独立构建与发布 |

> 这条与"门面/模块不再膨胀"的结论一致：**扩展层靠契约+宿主子系统承载，不靠新模块堆叠。**

## 2. 两类扩展点

### 2.1 能力型（实现端口：新增/替换能力）

| 端口 | 现有落点 | 缺口 |
|---|---|---|
| ToolProvider | `alice-tool-gateway`（Registry/ExecutionEngine） | 统一清单、权限、冲突解决 |
| ModelProvider | `alice-model`（ModelProvider/Supplier） | 同上 |
| MemoryBackend | `alice-memory-vault` | 同上 |
| GuardPolicy | `alice-guardrail`（Pre/Post Validator） | 同上 |
| EnvAdapter / MCP | `alice-env-adapter`（Stdio/Sse Transport） | 同上 |
| SandboxProvider | 已有接口（`SandboxProvider<T>`） | 同上 |
| Transport | 本轮新增（proto 契约 × 传输层） | 作为一种扩展实现纳入统一生命周期 |

### 2.2 行为型（钩子：拦截/改写流程）——**当前完全缺失**

Alice 现状只有**只读监听**（`KernelTraceListener` / `EnvEventListener`），没有"可拦截、可改写、可短路"的 hook 契约。

对齐 pi 事件族的钩子清单（★ = 可拦截/改写，其余为观察）：

| 钩子 | 能力 | pi 对应 |
|---|---|---|
| `before_agent_start` ★ | 改 system prompt / 注入上下文 | `before_agent_start` |
| `tool_call` ★ | **拦截/阻断/改参**（如 `rm -rf` 确认） | `tool_call`（返回 `{block:true}`） |
| `tool_result` ★ | 改写工具结果（脱敏/截断） | `tool_result` |
| `message_*` | 观察消息流（流式） | `message_start/update/end` |
| `turn_start/end`、`agent_start/end/settled` | 观察轮次/落定 | 同名 |
| `session_start/shutdown/before_switch` ★ | 会话生命周期与切换拦截 | `session_*` |
| `model_select` ★ | 拦截模型选择 | `model_select` |
| `input` ★ | 改写用户输入 | `input` |

## 3. 工程要素（扩展层五件套）

1. **清单 manifest**：`id / name / version / apiVersion(requiresProto) / capabilities[] / permissions[] / entrypoint / order`
2. **发现与装载**：SPI（ServiceLoader）+ 目录扫描（`~/.alice/extensions/`、项目 `.alice/extensions/`）；启用/禁用/热重载/卸载
3. **调用规则**：钩子顺序显式（`order`）+ 超时 + 可观测（耗时/结果入 ops/trace）；能力端口注册名冲突需显式声明覆盖
4. **失败隔离**：扩展抛错/超时 ⇒ 记 `EXTENSION_ERROR` 事件 + **降级继续**，绝不打断轮次（pi `extension_error` 的教训）
5. **权限与审计**：清单声明可做什么（fs / net / exec / 模型调用）；装载=需显式信任；启停与调用留痕

**版本协商**：`requiresProto` 与 `alice-agent-proto` 协议版本比对，不兼容 ⇒ 拒绝装载 + 可读原因。

**扩展注册的命令/事件**：走 proto 版本纪律（帧带 `v`；新命令/事件先进 proto，或经受限的 `ExtensionCommand` 通道）。

## 4. 与架构铁律的关系

| 铁律 | 在扩展层的体现 |
|---|---|
| 契约唯一真源 | 端口/清单 schema 在 proto；扩展不得私自定义对外帧 |
| 依赖倒置 | core 只认端口；扩展只依赖 proto（不得 import core 内部） |
| 传输可插拔 | `Transport` 本身即一种扩展实现 |
| 失败隔离 | 扩展错误 = 事件 + 降级，不打断会话 |
| 单写者 / 一轮一锁 | 钩子运行在宿主调度内，不得绕开轮锁直写会话 |

## 5. 验收（Gate）

- [ ] 不重编译核心，装载三类扩展：**a)** 新工具；**b)** 拦截危险命令（`tool_call` 返回 block）；**c)** 注入上下文（`before_agent_start` 改写 prompt）
- [ ] 扩展抛错/超时：轮次继续；`EXTENSION_ERROR` 可在 trace 里查到（extensionId / 版本 / 错误摘要）
- [ ] 禁用/卸载后行为回滚；重新启用（含热重载）后生效
- [ ] 权限：清单外操作被拒 + 审计留痕

## 6. 开放问题（待拍板）

| # | 问题 | 选项 |
|---|---|---|
| Q1 | 钩子范围 | 先"只读 + 可 block"（安全） vs 一次到位"可改写" |
| Q2 | 隔离级别 | 同进程 SPI / 独立 ClassLoader / 独立进程（MCP 已具隔离）——分层支持否 |
| Q3 | 分发形态 | 仓库外独立 artifact vs 仓库内 `extensions/` 目录（pi 式） |
| Q4 | 权限模型 | 纯声明式（清单） vs 运行时询问（pi `ctx.ui.confirm`） |
