# Alice Memory Vault 模块结构（分包后实际布局）

按**领域职责+分层**拆分，贴合DDD内存记忆库架构。

```
org.cland.alice.memory
├── agent                    // 会话、上下文主体
│   ├── AgentSession.java
│   └── Context.java
├── core                     // 记忆核心模型
│   ├── Experience.java
│   ├── Knowledge.java
│   ├── MemorySet.java
│   ├── Step.java
│   ├── Summary.java
│   └── SOP.java
├── vault                    // 三类记忆仓库
│   ├── EpisodicVault.java
│   ├── SemanticVault.java
│   └── ProceduralVault.java
├── storage                  // 存储抽象+实现
│   ├── StorageBackend.java
│   └── InMemoryStorageBackend.java
├── router                   // 记忆路由、汇总策略
│   ├── MemoryRouter.java
│   ├── MemorySummarizer.java
│   └── DefaultMemorySummarizer.java
├── controller               // 仓库对外入口
│   └── VaultController.java
```

## 分包依据

| 分包 | 包含类 | 职责 |
|---|---|---|
| **agent** | AgentSession, Context | 会话上下文运行时实体 |
| **core** | Experience, Knowledge, MemorySet, Step, Summary, SOP | 领域基础数据模型 |
| **vault** | EpisodicVault, SemanticVault, ProceduralVault | 情景/语义/过程三类记忆仓库 |
| **storage** | StorageBackend, InMemoryStorageBackend | 存储接口与内存实现 |
| **router** | MemoryRouter, MemorySummarizer, DefaultMemorySummarizer | 记忆检索、摘要加工逻辑 |
| **controller** | VaultController | 统一对外调度入口 |

## 跨包依赖关系

```
controller ──→ router ──→ vault ──→ core
    │                    │
    └──→ storage ────────┘
         agent ──────────┘
```

- `controller` 依赖所有其他包
- `router` 依赖 `agent`、`core`、`vault`
- `vault` 依赖 `agent`、`core`
- `agent`、`core`、`storage` 不依赖其他包（基础层）
