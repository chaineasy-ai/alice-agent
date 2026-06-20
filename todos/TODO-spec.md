---
title: "TODO - Alice Agent Specification Board"
summary: "Master task specification board covering all modules"
read_when:
  - "planning, tracking, or reviewing overall project progress"
  - "checking status of all module tasks at a glance"
  - "updating the master task board after completing milestones"
scope:
  - "alice-bootstrap"
  - "alice-core-agent"
  - "alice-core-planner"
  - "alice-model"
  - "alice-env-adapter"
  - "alice-tool-gateway"
  - "alice-guardrail"
  - "alice-memory-vault"
  - "alice-agent-command"
  - "alice-facade-cmd"
  - "alice-facade-tui"
status: "active"
updated: "2026-06-14"
---
# TODO-spec: Alice Agent 项目任务规范看板

> 遵循 [GFM Markdown 任务列表规范](./docs/spec/GFM-Markdown-任务列表规范.md)
> 格式约定：`- [ ]` 待办 | `- [x]` 已完成 | `- [/]` 执行中 | `- [-]` 已取消 | `- [!]` 失败/阻塞
> 行内元数据：`[key:: value]`
> 缩进 4 空格 = 子任务层级

---

# 目标：Alice Agent 模块化框架开发与迭代

## 一、核心框架层 (Core Framework)

### □ 1.1 alice-core-agent — 核心Agent引擎
- [/] Agent 运行循环 (Run Loop) 重构 [priority:: high] [owner:: core]
    - [x] 基础 Tool Calling 循环实现
    - [x] 流式 (Streaming) 响应支持
    - [x] 中断恢复机制 (WAL + Checkpoint) [priority:: high] [verify:: AgentExecutor 5 个生命周期点注入 WAL，编译通过]
    - [x] 并发执行上下文管理 [priority:: medium]
- [x] Memory / Context Window 管理 [priority:: high]
    - [x] getActiveContext() — Markdown 表格格式上下文状态
    - [x] clearMemory() — 清空短期记忆
    - [x] compactContext() — 写入长期记忆作为 Checkpoint 替代
    - [ ] 上下文窗口滑动策略
    - [ ] Token 使用监控与预警
- [ ] 多模型调度 (Multi-Provider) [priority:: medium]
    - [ ] 模型路由选择策略 (direct/ round-robin / fallback)
    - [ ] Provider 健康检查与熔断

### □ 1.2 alice-core-planner — 执行规划引擎
- [x] 基础 Task 分解实现
- [x] World Model 状态推理 [verify:: WorldModel.md]
- [/] 任务依赖图 (DAG) 执行器 [priority:: high]
    - [x] DAG 拓扑排序
    - [/] 并行任务调度 [priority:: high] [verify:: 并发任务执行测试]
    - [ ] 任务重试 & 回退策略 [priority:: medium]
- [ ] 动态重规划 (Re-planning) [priority:: medium]
    - [ ] 执行中感知环境变化并调整计划
    - [ ] 部分失败时的局部重规划

### □ 1.3 alice-model — AI模型抽象层
- [x] 基础 OpenAI Chat Completions 适配
- [/] 多 Provider 适配器 [priority:: high] [tool:: ModelAdapter]
    - [x] OpenAI 适配
    - [x] Anthropic Claude 适配 [priority:: high] [verify:: ClaudeSupplierSpec 12 tests pass]
    - [ ] 本地模型 (Ollama / vLLM) 适配 [priority:: medium]
    - [ ] Azure OpenAI 适配 [priority:: low]
- [ ] 自定义 Provider SPI 扩展 [priority:: medium]
    - [ ] 插件化注册机制
    - [ ] Provider 配置热加载

## 二、工具与环境适配层 (Tool & Environment)

### □ 2.1 alice-tool-gateway — 工具执行网关
- [x] 工具注册与发现 [priority:: high]
    - [x] 基础注解式注册
    - [x] 工具参数 Schema 生成 (JSON Schema)
    - [x] 工具执行沙箱 (Sandbox) [priority:: high] [verify:: 沙箱隔离测试]
- [x] 内置工具集 (BuiltinTools) 实现 [priority:: high]
    - [x] read_file / write_file / grep / run [status:: done]
    - [x] list_dir / file_exists / search_file / remove_file [status:: done]
    - [/] web_search (DuckDuckGo) [status:: done, future:: 替换为 Tavily/Bing]
- [x] 内置工具集测试覆盖 (38 tests, 2 @IgnoreIf) [owner:: hole]
- [ ] web_search 网络 provider 重构 [priority:: medium] [future:: 替换 DuckDuckGo 为 Tavily/Bing Search API]
    - [ ] 提取 SearchProvider 接口（可插拔）
    - [ ] DuckDuckGoSearchProvider（当前实现，保留为 fallback）
    - [ ] BingSearchProvider / TavilySearchProvider
    - [ ] Provider 通过配置 key 切换
- [ ] 工具执行结果缓存 [priority:: medium]
    - [ ] LRU 缓存策略
    - [ ] 缓存命中统计
- [ ] 动态工具加载 (Hot-Plug) [priority:: low]
    - [ ] 运行时加载外部 JAR 工具
    - [ ] 工具版本管理

### □ 2.2 alice-env-adapter — 环境适配器
- [x] 本地 Shell 执行 [priority:: high]
    - [x] 跨平台 (Windows / Linux / macOS) 命令适配
    - [x] 超时与资源限制
    - [x] 输出截断与分页读取
- [ ] 远程 SSH 执行 [priority:: high]
    - [ ] 密钥管理与自动认证
    - [ ] SCP 文件传输
    - [ ] 会话复用 (Connection Pool)
- [ ] DockerSendbox 环境适配 [priority:: medium]
    - [ ] Docker Exec 接口

### □ 2.3 alice-guardrail — 安全护栏
- [ ] 命令白名单/黑名单 [priority:: high]
- [ ] 敏感信息过滤 (Secret Redaction) [priority:: high]
    - [ ] API Key / Token 检测与脱敏
    - [ ] 日志输出审查
- [ ] 执行审计日志 [priority:: medium]
    - [x] 完整调用链追踪
    - [ ] 审计日志持久化 use langfuse

## 三、记忆与持久化层 (Memory & Persistence)

### □ 3.1 alice-memory-vault — 记忆库
- [/] 工作记忆 (Working Memory) [priority:: high] [verify:: AWL&CheckPoint.md]
    - [x] 短期上下文存储
    - [/] 工作记忆序列化 (Checkpoint) [priority:: high]
    - [x] WAL + Checkpoint 双轨制实现 [verify:: AgentExecutor 集成完成，309 个测试通过]
    - [ ] 检查点自动保存间隔策略 [priority:: low]
- [x] 长期记忆 (Long-term Memory) [priority:: medium]
    - [x] JVectorSemanticVault — 基于 JVector 4.x 的嵌入式向量搜索引擎 [verify:: JVectorSemanticVaultSpec 18 tests]
    - [ ] 记忆摘要与压缩
- [x] 遗忘机制 [priority:: low] [verify:: Forgetting.md]
    - [x] 基于重要度的遗忘策略（EpisodicVault）
    - [ ] 基于时间衰减的遗忘策略
    - [ ] 重要记忆锁定

## 四、接口门面层 (Facade)

### □ 4.1 alice-facade-cmd — 命令行门面
- [x] 基础 CLI 交互（picocli 单次任务模式）
- [x] 参数解析 (Picocli)
- [/] 彩色输出与进度显示 [priority:: medium]
    - [x] JSON / 文本双渲染器
    - [ ] 任务进度条
    - [ ] 状态图标与颜色方案
- [x] 交互式 Chat 模式 (JLine 3) [priority:: high] [ref:: TODO-alice-facade.md]
    - [x] JLine 3 依赖注入 (build.gradle + module-info)
    - [x] JLineChatSession 交互引擎（行编辑、历史、补全）
    - [x] ChatCommand 子命令实现
    - [x] dispatchCommand 补齐 5 个缺失分支

### □ 4.2 alice-facade-tui — TUI 门面
- [x] 基础 TUI 框架集成 (Lanterna)
- [x] 聊天界面 (Chat Widget)
- [/] 任务看板视图 [priority:: high] [verify:: QUICK_START.md]
    - [x] 思考链视图 (ThoughtComponent)
    - [ ] 实时任务状态更新
    - [ ] 任务树展示 (Markdown 渲染)
- [x] 指令分发链路补齐 [priority:: high] [ref:: TODO-alice-facade.md]
    - [x] dispatchAgentCommand 补齐 5 个缺失分支
    - [x] SlashCommand / CommandHandler 注册 /context /compact /feedback
- [x] JLine 3 依赖清理 [priority:: low] [ref:: TODO-alice-facade.md]
    - [x] 评估并移除/保留 JLine 3
- [ ] 多 Session / 多 Tab 支持 [priority:: low]

## 五、指令驱动层 (Command Layer)

### □ 5.1 alice-agent-command — 指令抽象层
- [x] 密封接口指令层级定义 (Sealed Interface)
    - [x] AgentCommand 顶层接口 (Execution / Capability / Alignment / Control)
    - [x] ExecutionCmd 分支 (/run, /exec)
    - [x] CapabilityCmd 分支 (/skill, /rules, /reload)
    - [x] AlignmentCmd 分支 (/model)
    - [x] ControlCmd 分支 (/new, /feedback, /exit, /clear, /context, /compact)
- [x] AgentCommand.parse() 工厂方法 (斜杠命令解析)
- [x] Agent 核心接口补齐 (getActiveContext / clearMemory / compactContext / switchModel / injectFeedback) [priority:: high] [ref:: TODO-alice-facade.md]
- [x] JLineChatSession 单元测试 (5 tests)
- [x] AgentFacadeSpec 单元测试 (9 tests)

## 六、部署与运维 (DevOps & Release)

### □ 6.1 构建与打包
- [x] Gradle 多模块构建
- [/] GraalVM Native Image 编译 [priority:: high] [tool:: gradle]
    - [x] 基础 native-image 配置
    - [/] Reflection 配置完善 [priority:: high] [verify:: 所有模块 native-image 测试通过]
    - [ ] 镜像尺寸优化 (< 50MB)
- [ ] CI/CD 流水线 [priority:: medium]
    - [ ] GitHub Actions 配置
    - [ ] 自动化测试与发布

### □ 6.2 文档与规范
- [x] CHANGELOG 规范 (Conventional Commits)
- [/] 模块文档同步至 ShowDoc [priority:: medium]
    - [x] (spec)CHANGELOG.md 同步
    - [ ] 各模块 DESIGN.md 同步
    - [ ] API 文档自动生成
- [ ] E2E 测试覆盖 [priority:: high]
    - [/] 基础执行流程测试 [priority:: high]
    - [ ] 异常恢复场景测试
    - [ ] 多模型兼容性测试

## 七、长期规划 (Future)

### □ 7.1 可观测性
- [ ] OpenTelemetry 集成 [priority:: low]
- [ ] 指标采集 (Metrics) [priority:: low]
- [ ] 分布式追踪 (Tracing) [priority:: low]

### □ 7.2 生态系统
- [ ] 插件市场 (Plugin Registry) [priority:: low]
- [ ] 工具市场 (Tool Store) [priority:: low]
- [ ] Agent 模板仓库 [priority:: low]

---

## 状态汇总

| 状态 | 计数 | 说明 |
|------|------|------|
| `- [x]` 已完成 | 41 | 已实现并验证通过 |
| `- [/]` 执行中 | 3 | 正在开发实现中 |
| `- [ ]` 待执行 | 39 | 未开始 |
| `- [!]` 失败/阻塞 | 0 | 当前无阻塞项 |
| **总计** | **83** | — |

> 最后更新：2026-06-15
> ✅ TODO-memory-vault 全部完成（90/90，309 tests）
> ✅ JVectorSemanticVault — JVector 4.x 嵌入式向量搜索引擎（18 tests）
> ✅ 核心框架层 → 记忆与持久化层全线贯通
> ✅ /sub-agent — Multi-Agent via ACP Protocol (003): Phases 3-7 completed (21→59 tasks, 38 new tasks)
> ✅ Anthropic Claude 适配器 — ClaudeSupplier 12 tests pass
