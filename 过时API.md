# 过时 API 跟踪

## ✅ 已修复 (20260612)

### JVectorSemanticVault.java — 3 处修复

| 位置 | 过时 API | 替换为 | 说明 |
|------|----------|--------|------|
| `CollectionIndex.add()` | `GraphIndexBuilder.addGraphNode(int, RandomAccessVectorValues)` | `addGraphNode(int, VectorFloat<?>)` | 直接传入 `vectorList.get(localId)` 而非 `ravv` |
| `ListBackedVectorValues.getVector()` | 接口方法标注 `@Deprecated` | `@SuppressWarnings("deprecation")` | 方法为抽象方法，必须实现；增加 `vectorValue()` 委托 |
| `CollectionIndex.search()` | `GraphIndex.size()` | `vectorList.isEmpty()` | 用本地列表判断空索引，避免调用已过时的 `size()` |

### AgentExecutor.java — 1 处修复

| 位置 | 过时 API | 替换为 | 说明 |
|------|----------|--------|------|
| `dispatchToolCall()` | `ToolRegistry.execute(String, Map)` | `ExecutionEngine.invoke(String, Map)` | 新 API 提供沙箱隔离+超时控制+结构化 `ToolResult` |

---

## 说明

- `ToolRegistry.execute()` 标注 `@Deprecated`，建议迁移至 `ExecutionEngine`
- `JVector 4.0.0-beta.6` 中 `GraphIndex.size()` 和 `RandomAccessVectorValues.getVector()` 均已过时
  - `size()` 替换方案：自行维护节点计数或使用 `getIdUpperBound()`
  - `getVector()` 因是抽象方法，保留实现并添加 `@SuppressWarnings`
