---
title: "alice-memory-vault: implementation overview"
summary: "Implemented vault components, WAL subsystem, and JVectorSemanticVault architecture notes"
read_when:
  - "implementing or modifying memory vault components"
scope:
  - "alice-memory-vault"
status: "active"
updated: "2026-06-14"
---

# alice-memory-vault: 实现清单

## Vault 组件

| Vault 类型 | 实现类 | 引擎 | 测试数 |
|------------|--------|------|--------|
| **Episodic** | `WalEpisodicVault` | WAL (File/InMemory) | 17 + CrashRecoveryE2E |
| **Semantic** | `JVectorSemanticVault` | JVector 4.x HNSW COSINE | 18 |
| **Semantic** | `InMemorySemanticVault` | HashMap 线性扫描 | — |
| **Procedural** | `InMemoryProceduralVault` | HashMap | — |

## WAL 子系统

| 组件 | 描述 |
|------|------|
| `FileWalStore` | 文件持久化 WAL |
| `InMemoryWalStore` | 内存 WAL（测试用） |
| `WalSession` | 会话 WAL 封装，单一事实来源 |
| `WalAppender` | 追加写入器 |
| `RecoveryEngine` | 崩溃恢复重建 |
| `CheckpointManager` | lastAppliedMessageId 管理 |
| `WalCompactor` | 后台压缩（Checkpoint 截断 + minRetentionCount 保护） |

## 其他组件

| 组件 | 描述 |
|------|------|
| `DreamingEngine` | 后台记忆整合与模式结晶 |
| `Crystallizer` | 模式结晶（聚类 + 抽象） |
| `ConflictResolver` | 记忆冲突消解 |
| `SessionStateManager` | 会话状态管理 |

## JVectorSemanticVault 技术笔记

### Collection 隔离
每个 Collection 独立 `OnHeapGraphIndex`，检索不互相干扰。

### 文本向量化
FNV 哈希投影 → **128 维** L2 归一化浮点向量。确定性词级匹配（非语义 embedding）。

### HNSW 参数
M=16, efConstruction=40, COSINE 相似度, 阈值 DEFAULT_SIMILARITY_THRESHOLD=0.3。

### 增量索引
首个文档 `GraphIndexBuilder.build()`，后续 `addGraphNode()`。删除通过 `markNodeDeleted()`，不重建索引。

### ⚠️ 踩坑记录

**VectorFloat 实例化**：`ArrayVectorFloat(float[])` 是 package-private。必须通过 `VectorizationProvider.getInstance().getVectorTypeSupport().createFloatVector(data)` 创建。

**索引节点 ID**：`MapRandomAccessVectorValues` 导致 `ClassCastException`（JVector 内部做 `ArrayVectorFloat` 向下转型）。推荐基于 `List<VectorFloat<float[]>>` 实现 `RandomAccessVectorValues`，nodeId = list index 连续分配。

**维度选择**：32 维随机碰撞严重。升级到 128 维有效缓解。

### 依赖与模块
- 零外部 Java 依赖（JVector 4.x 纯 Java）
- JPMS: `--add-reads alice.agent.alice.memory.vault.main=ALL-UNNAMED`
