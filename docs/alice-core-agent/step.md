---
title: "Step 抽象层 — Macro Intent / Micro Action"
summary: "Plan step 的分层设计：Macro 层业务意图 (Intent) 与 Micro 层执行动作 (Action) 的分离"
read_when:
  - "understanding Plan.Step and Action.Type design"
  - "adding new step types or action types"
  - "debugging the Planner → Executor handoff"
scope:
  - "alice-core-planner"
  - "alice-core-agent"
status: "active"
updated: "2026-07-13"
---

# Step 抽象层

Plan step 分为两层，中间由 `Intent.toActionString()` 映射。

```
Macro 层 (Planner 产出)              Micro 层 (Executor 执行)
──────────────────────               ──────────────────────
Plan.Intent                           Action.Type / 具体工具名
                                       (AgentExecutor 中映射)

  ANALYZE  ──────toActionString()──→  LLM_INFERENCE
  SEARCH   ──────toActionString()──→  TOOL_CALL
  CODE     ──────toActionString()──→  LLM_INFERENCE
  GENERATE ──────toActionString()──→  LLM_INFERENCE
  ANSWER   ──────toActionString()──→  FINISH
  FINISH   ──────toActionString()──→  FINISH
  REVISION ──────toActionString()──→  REVISION
```

## 为什么分层

Intent 是**业务意图**（要分析、要搜索、要编码），Action 是**实现方式**（调 LLM、调工具）。规划器只关心"做什么方向"，不关心"用什么基础设施实现"。

### 改之前的问题

```
// 规划器直接暴露实现细节 — 基础设施泄漏到业务层
Step.of("LLM_INFERENCE", modelId, params);   // ❌ 规划器不应该知道 LLM
Step.of("TOOL_CALL", "search", params);       // ❌ 半业务半实现
```

### 改之后

```
// 规划器只出业务意图
Step.of(Intent.ANALYZE, modelId, params);    // ✅ "需要分析"
Step.of(Intent.SEARCH, "search", params);     // ✅ "需要搜索"

// 执行层负责映射到具体实现
firstStep.intent() → switch {
    case ANALYZE → Action.llmInference(...)
    case SEARCH  → Action.toolCall(...)
    ...
}
```

## Plan.Intent 枚举

定义在 `alice-core-planner` 的 `Plan.java` 中：

```java
public enum Intent {
    ANALYZE,    // 需要分析 / 推理
    SEARCH,     // 需要搜索 / 查找信息
    CODE,       // 需要编写代码
    GENERATE,   // 需要生成内容
    ANSWER,     // 直接回答
    FINISH,     // 任务完成
    REVISION;   // 需要修订

    public String toActionString() {
        return switch (this) {
            case ANALYZE, CODE, GENERATE -> "LLM_INFERENCE";
            case SEARCH -> "TOOL_CALL";
            case ANSWER, FINISH -> "FINISH";
            case REVISION -> "REVISION";
        };
    }
}
```

## Plan.Step

每个 Step 承载：

```java
Step step = Step.of(Intent.ANALYZE, "gpt-4o", params, thought);
step.intent();     // Intent.ANALYZE
step.target();     // "gpt-4o"（模型 ID / 工具名）
step.parameters(); // {prompt: "...", ...}
step.thought();    // LLM 推理文本（可选）
```

向后兼容：`.actionType()` 标记为 `@Deprecated`，返回 `intent.toActionString()`。

## AgentExecutor 中的消费

`planToIntent()` 将 Plan 的第一步转为执行 Map：

```java
switch (firstStep.intent()) {
    case FINISH   → Map.of("type", "FINISH")
    case REVISION → Map.of("type", "REVISION", "feedback", ...)
    case SEARCH   → Map.of("type", "TOOL_CALL", "target", ..., "parameters", ...)
    default       → Map.of("type", "LLM_INFERENCE", "intent", intent.name(), ...)
}
```

## 在 MCTS 中的使用

MCTS（`MctsEngine`）的 expander 产生 `ThinkingNode`，其 `actionType` 是 String 标签。`SlowPathStrategy` 在将 MCTS 结果转为 Plan step 时映射：

```java
// SlowPathStrategy
private static Plan.Intent toIntent(String actionType) {
    return switch (actionType) {
        case "LLM_INFERENCE" -> Plan.Intent.ANALYZE;
        case "TOOL_CALL"    -> Plan.Intent.SEARCH;
        case "OBSERVE"      -> Plan.Intent.ANALYZE;
        case "REVISION"     -> Plan.Intent.REVISION;
        default             -> Plan.Intent.ANALYZE;
    };
}
```

## 添加新的 Intent

1. 在 `Plan.Intent` 枚举中新增值
2. 在 `toActionString()` 中指定映射到哪个 Action.Type
3. 在 `AgentExecutor.planToIntent()` 的 switch 中处理新 Intent
4. 在 `GuardrailVerificatorAdapter.toIntent()` 和 `StaticPlanner.toIntent()` 中添加映射（如有需要）
