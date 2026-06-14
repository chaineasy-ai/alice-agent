# 基于 sessionId 打通 Langfuse 与 WAL 日志链路设计方案
## 一、方案核心思路
利用系统原生 **sessionId** 作为唯一关联标识，将 Langfuse 链路追踪（Trace/Span）与底层 WAL 持久化日志打通。以轻量关联字段作为纽带，在不改造原有系统架构、不侵入底层逻辑的前提下，实现 AI 应用行为观测与底层执行日志双向溯源。

## 二、完整数据流转链路
### 前提
业务系统任务启动时，自动生成全局唯一 `sessionId`，作为全链路统一标识。

1. **业务层发起任务**
系统创建执行任务，生成底层事务唯一标识 `sessionId`（示例：`1001`）。

2. **AI 观测层：Langfuse 埋点关联**
Java 应用调用 Langfuse SDK 创建 Span，将 `sessionId` 写入元数据（Metadata），完成链路绑定。
```java
trace.span(new CreateSpanRequest.Builder()
     .name("alice-env-adapter")
     .metadata(MapUtils.of("sessionId", "1001")) // 核心关联字段
     .build());
```

3. **底层执行层：WAL 日志记录**
`EnvAdapter` 模块将执行命令、运行状态与 `sessionId` 按顺序追加写入 WAL 文件。
> 说明：WAL 仅保留底层原始执行数据，不新增应用层追踪字段，保证高性能与数据纯粹性。

## 三、问题排查与复盘流程
依托 `sessionId` 可实现**双向快速溯源**，适配线上 Debug、故障排查、安全审计场景。

### 场景1：由 Langfuse 定位底层异常
1. 在 Langfuse 可视化界面发现 Agent 工具调用、模型行为异常；
2. 进入对应 Span，从 Metadata 中提取 `sessionId`；
3. 在服务器通过关键字检索 WAL 日志，查看原始执行记录、退出码、环境报错等底层真实信息。
```bash
# 检索指定会话的 WAL 日志
grep "1001" wal.log
```

### 场景2：由 WAL 日志反向追溯 AI 行为
1. 监控发现 WAL 日志中某一 `sessionId` 出现环境错误、执行失败等问题；
2. 使用该 `sessionId` 在 Langfuse 后台检索；
3. 还原完整链路：关联对应 Trace、操作用户、请求时间、原始 Prompt、模型推理过程及工具调用上下文。

## 四、方案优势与总结
1. **低侵入、高复用**
    - WAL 保持原有 Append-Only 设计，沿用自带的 `sessionId`，无需改造底层存储逻辑；
    - Langfuse 仅追加一行元数据配置，代码改动极小。
2. **职责边界清晰**
    - WAL：负责**系统级物理审计**，留存底层原始执行日志，保障数据可靠、写入高性能；
    - Langfuse：作为**AI 全链路可视化观测工具**，聚焦模型、Agent、调用流程的追踪与展示。
3. **双向溯源能力**
单依靠 `sessionId` 即可串联上层 AI 行为与下层系统执行日志，兼顾线上观测、故障定位、安全审计等诉求，架构简洁且实用性强。
