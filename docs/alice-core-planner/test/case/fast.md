---
title: "Test Case — Planner FastPath Strategy"
summary: "Comprehensive test case specification for FastPath Strategy (System 1) — StrategySelector routing, FastPathStrategy plan generation, Plan value object, TokenBudget, and all boundary/edge conditions."
read_when:
  - "implementing or reviewing FastPath tests in PlannerServiceSpec"
  - "debugging StrategySelector routing or FastPathStrategy behavior"
  - "adding new FastPath test coverage"
scope:
  - "alice-core-planner"
  - "docs/alice-core-planner/test/case"
status: "active"
updated: "2026-06-27"
---

# Test Case — FastPath (System 1)

## 1. Overview

FastPath is the **System 1 (快思考)** path of the planner's dual-path decision engine. It handles low-complexity tasks by generating a simple plan: `[LLM_INFERENCE → FINISH]`.

### Decision Flow

```
StrategySelector.select(context)
  │
  ├── defaultComplexityCheck()
  │     ├── prompt.length() > 200  → SlowPath
  │     ├── keyword match          → SlowPath
  │     ├── has feedback           → SlowPath
  │     ├── has error              → SlowPath
  │     └── else                   → ✅ FastPath
  │
  └── FastPathStrategy.decide()
        ├── result present? → FINISH
        └── else → [LLM_INFERENCE(targetModel), FINISH]
```

### Coverage Map

```
┌──────────────────────────────────────────────────────┐
│                 FastPath Test Coverage                │
│                                                      │
│  FP-T01  Plan value object (build, factory, step)   │
│  FP-T02  StrategySelector: 简单 → FastPath          │
│  FP-T03  StrategySelector: 复杂关键词 → SlowPath     │
│  FP-T04  StrategySelector: 长 prompt → SlowPath      │
│  FP-T05  StrategySelector: 反馈/错误 → SlowPath      │
│  FP-T06  StrategySelector: 自定义复杂度函数           │
│  FP-T07  FastPathStrategy: 生成 FAST_PATH Plan       │
│  FP-T08  FastPathStrategy: result 存在时直接 FINISH  │
│  FP-T09  TokenBudget: 额度耗尽检测                   │
│  FP-T10  TokenBudget: unlimited 模式                 │
│  FP-T11  PlannerService: 集成 FastPath               │
│  FP-T12  PlannerService: result 短路 → FINISH        │
└──────────────────────────────────────────────────────┘
```

---

## 2. Test Cases

### FP-T01: Plan 值对象构建

| Field | Value |
|-------|-------|
| **Target** | `Plan.builder()` / `Plan.fastPath()` / `Plan.Step.of()` |
| **Source** | `PlannerServiceSpec` — `"Plan should be buildable with steps and type"`, `"Plan.fastPath should create single-step fast path plan"`, `"Plan.Step should convert to action map"` |
| **Status** | 🟩 GREEN |

**Test scenario**:

```groovy
// Builder pattern
Plan plan = Plan.builder()
    .type(Plan.Type.FAST_PATH)
    .summary("Test plan")
    .addStep(Plan.Step.of("LLM_INFERENCE", "gpt-4o-mini"))
    .addStep(Plan.Step.of("FINISH", "FINISH"))
    .build()

plan.type()          // FAST_PATH
plan.steps().size()  // 2
plan.steps()[0].actionType()  // "LLM_INFERENCE"
plan.steps()[1].actionType()  // "FINISH"
```

**Edge cases covered**:

| Edge | Input | Expected | Covered |
|------|-------|----------|---------|
| 空 steps | builder 不调用 addStep | steps() 为空列表 | ❌ 未覆盖 |
| null type | builder 不设置 type | type() == null | ❌ 未覆盖 |
| Step 参数含 null | `parameters=null` | toActionMap 不 NPE | ❌ 未覆盖 |
| Step 含 thought | `thought="Reason"` | toActionMap["thought"] == "Reason" | ✅ |
| fastPath 工厂方法 | `Plan.fastPath("Quick","LLM_INFERENCE","gpt-4o-mini")` | 1 step, type=FAST_PATH | ✅ |
| staticPlan 工厂方法 | `Plan.staticPlan("s", [step1, step2, step3])` | 3 steps, type=STATIC | ✅ |

### FP-T02: StrategySelector 简单任务路由到 FastPath

| Field | Value |
|-------|-------|
| **Target** | `StrategySelector.select(Map)` |
| **Source** | `PlannerServiceSpec` — `"StrategySelector should route simple tasks to fast path"` |
| **Input** | `[prompt: "hello"]` |
| **Expected** | `plan.type() == FAST_PATH` |
| **Status** | 🟩 GREEN |

**判定链路**:

```
prompt="hello"
  length=5          → ≤ 200 ✓
  no complex keyword → ✓
  no feedback/error  → ✓
  → isComplex=false → FastPath
```

### FP-T03: StrategySelector 复杂关键词路由到 SlowPath

| Field | Value |
|-------|-------|
| **Target** | `StrategySelector.select(Map)` |
| **Source** | `PlannerServiceSpec` — `"StrategySelector should route by keyword to slow path"` |
| **Input** | 15 个复杂关键词（中英文） |
| **Expected** | 所有关键词触发 `SLOW_PATH` |
| **Status** | 🟩 GREEN |

**关键词覆盖矩阵**:

| 语言 | 关键词 | 覆盖 |
|------|--------|------|
| English | `analyze`, `compare`, `contrast`, `evaluate`, `synthesize`, `plan`, `strategy`, `multi-step`, `complex`, `detailed` | ✅ |
| 中文 | `分析`, `比较`, `评估`, `计划`, `策略`, `综合` | ✅ |

**表驱动测试 (where block)**:

```groovy
where:
k << ["analyze this", "compare X and Y", "evaluate options",
      "synthesize findings", "create a plan", "strategy session",
      "multi-step workflow", "complex task", "detailed report",
      "分析报告", "比较方案", "评估结果", "制定计划",
      "调整策略", "综合意见"]
```

### FP-T04: StrategySelector 长 prompt 路由到 SlowPath

| Field | Value |
|-------|-------|
| **Target** | `StrategySelector.select(Map)` |
| **Source** | `PlannerServiceSpec` — `"StrategySelector should route long prompt to slow path"` |
| **Input** | `[prompt: "A" * 250]` (长度 > 200 阈值) |
| **Expected** | `plan.type() == SLOW_PATH` |
| **Status** | 🟩 GREEN |

**阈值边界**:

| Input length | Expected | Status |
|-------------|----------|--------|
| 0 (空) | FAST_PATH | ❌ 未覆盖 |
| 1 | FAST_PATH | ❌ 未覆盖 |
| 200 | FAST_PATH (等于阈值，不触发) | ❌ 未覆盖 |
| 201 | SLOW_PATH (>阈值) | ❌ 未覆盖 |
| 250 | SLOW_PATH | ✅ |

### FP-T05: StrategySelector 反馈/错误路由到 SlowPath

| Field | Value |
|-------|-------|
| **Target** | `StrategySelector.select(Map)` |
| **Source** | `PlannerServiceSpec` — `"StrategySelector should route by feedback to slow path"`, `"StrategySelector should route by error to slow path"` |
| **Status** | 🟩 GREEN |

**场景**:

| Context | Expected | Status |
|---------|----------|--------|
| `[prompt:"hello", lastFeedback:"too slow"]` | SLOW_PATH | ✅ |
| `[prompt:"hello", error:"timeout"]` | SLOW_PATH | ✅ |
| `[prompt:"hello", lastFeedback:null, error:null]` | FAST_PATH | ✅ (隐式) |
| `[prompt:"hello"]` (key 不存在) | FAST_PATH | ✅ |

### FP-T06: StrategySelector 自定义复杂度函数

| Field | Value |
|-------|-------|
| **Target** | `StrategySelector.complexityFunction(Function)` |
| **Source** | `PlannerServiceSpec` — `"StrategySelector should accept custom complexity function"` |
| **Status** | 🟩 GREEN |

**注入自定义判定逻辑**:

```groovy
def selector = StrategySelector.builder()
    .fastPath(fastPath)
    .slowPath(slowPath)
    .complexityFunction({ ctx -> "custom_slow".equals(ctx.get("mode")) })
    .build()

selector.select([prompt: "hello", mode: "fast"]).type()        // FAST_PATH
selector.select([prompt: "hello", mode: "custom_slow"]).type() // SLOW_PATH
```

### FP-T07: FastPathStrategy 生成 FAST_PATH Plan

| Field | Value |
|-------|-------|
| **Target** | `FastPathStrategy.decide(Map)` |
| **Source** | `PlannerServiceSpec` — `"FastPathStrategy should generate fast path plan"` |
| **Input** | `[prompt: "What is Java?"]` |
| **Expected** | `plan.type() == FAST_PATH`, 2 steps: `[LLM_INFERENCE → FINISH]` |
| **Status** | 🟩 GREEN |

**Plan 结构验证**:

```groovy
plan.type() == Plan.Type.FAST_PATH
plan.steps().size() == 2
plan.steps()[0].actionType() == "LLM_INFERENCE"
plan.steps()[0].target() == "gpt-4o-mini"       // 来自 ModelSession
plan.steps()[1].actionType() == "FINISH"
plan.metadata()["path"] == "fast"
```

### FP-T08: FastPathStrategy result 存在时直接 FINISH

| Field | Value |
|-------|-------|
| **Target** | `FastPathStrategy.decide(Map)` |
| **Source** | `PlannerServiceSpec` — `"FastPathStrategy should finish if result present"` |
| **Input** | `[prompt:"test", result:"done"]` |
| **Expected** | 1 step: `[FINISH]` |
| **Status** | 🟩 GREEN |

### FP-T09: TokenBudget 额度耗尽检测

| Field | Value |
|-------|-------|
| **Target** | `TokenBudget.consume()` / `isExhausted()` |
| **Source** | `PlannerServiceSpec` — `"TokenBudget should enforce limits"`, `"TokenBudget should track consumption"` |
| **Input** | `TokenBudget.of(3, 10)`, consume 3 次 |
| **Expected** | 第 3 次 consume 后 exhausted=true |
| **Status** | 🟩 GREEN |

**参数化场景**:

| maxTokens | consume 次数 | exhausted | Status |
|-----------|-------------|-----------|--------|
| 5 | 0 | false | ✅ |
| 3 | 1 | false | ✅ |
| 3 | 2 | false | ✅ |
| 3 | 3 | true | ✅ |
| 0 | 0 | true | ❌ 未覆盖 |
| 0 | 1 | — | ❌ 未覆盖 |

### FP-T10: TokenBudget unlimited 模式

| Field | Value |
|-------|-------|
| **Target** | `TokenBudget.unlimited()` |
| **Source** | `PlannerServiceSpec` — `"TokenBudget.unlimited should never exhaust"` |
| **Input** | 100 次 consume |
| **Expected** | 永不 exhausted |
| **Status** | 🟩 GREEN |

### FP-T11: PlannerService 集成 FastPath

| Field | Value |
|-------|-------|
| **Target** | `PlannerService.plan(Map)` |
| **Source** | `PlannerServiceSpec` — `"PlannerService should handle simple prompt via FastPathStrategy"` |
| **Input** | `[prompt: "Hello"]` |
| **Expected** | 返回非空 Plan, steps ≥ 1 |
| **Status** | 🟩 GREEN |

**完整调用链路**:

```
PlannerService.plan([prompt:"Hello"])
  → 无 result
  → StaticPlanner 未命中 (或不存在)
  → StrategySelector.select()
    → defaultComplexityCheck: "Hello" 5 chars, 无关键词 → FastPath
  → FastPathStrategy.decide()
    → Plan [LLM_INFERENCE/gpt-4o-mini, FINISH]
```

### FP-T12: PlannerService result 短路 → FINISH

| Field | Value |
|-------|-------|
| **Target** | `PlannerService.plan(Map)` |
| **Source** | `PlannerServiceSpec` — `"PlannerService should finish when result is present"` |
| **Input** | `[prompt:"test", result:"done"]` |
| **Expected** | 1 step: `[FINISH]` |
| **Status** | 🟩 GREEN |

**优先级**: result 检查先于 StaticPlanner 和 StrategySelector。

---

## 3. Test Data Matrix

| Test | Fixture | Input | Expected Type | Step Count | Key Assertion |
|------|---------|-------|---------------|------------|---------------|
| FP-T01a | Plan builder | FAST_PATH, summary, 2 steps | FAST_PATH | 2 | step[0].actionType() == "LLM_INFERENCE" |
| FP-T01b | Plan.fastPath | "Quick", "LLM_INFERENCE", "gpt-4o-mini" | FAST_PATH | 1 | factory method |
| FP-T01c | Plan.Step | TOOL_CALL, "search_api", params, thought | — | — | toActionMap has all fields |
| FP-T02 | StrategySelector | [prompt:"hello"] | FAST_PATH | — | routed to fast |
| FP-T03 | StrategySelector | 15 keywords | SLOW_PATH | — | keyword routing |
| FP-T04 | StrategySelector | 250-char prompt | SLOW_PATH | — | length > threshold |
| FP-T05 | StrategySelector | feedback / error | SLOW_PATH | — | context routing |
| FP-T06 | StrategySelector + custom fn | custom mode | matches fn | — | custom override |
| FP-T07 | FastPathStrategy | [prompt:"What is Java?"] | FAST_PATH | 2 | LLM_INFERENCE + FINISH |
| FP-T08 | FastPathStrategy | result:"done" | FAST_PATH | 1 | FINISH only |
| FP-T09 | TokenBudget | of(3,10), consume×3 | exhausted | — | exact limit |
| FP-T10 | TokenBudget | unlimited, consume×100 | not exhausted | — | unlimited |
| FP-T11 | PlannerService | [prompt:"Hello"] | FAST_PATH | ≥1 | full integration |
| FP-T12 | PlannerService | result:"done" | FAST_PATH | 1 | result shortcut |

---

## 4. Coverage Gaps

| Gap | Description | Priority | Suggestion |
|-----|-------------|----------|------------|
| 🟡 Plan 空 steps | `Plan.builder().build()` 后 steps() 行为 | Low | 添加空 steps 的防御性断言 |
| 🟡 Plan null type | `builder().addStep(...).build()` 未设 type | Low | 要么设置默认值，要么测试 null |
| 🟡 Step null parameters | `Plan.Step.of("T","t",null,"thought")` | Low | toActionMap null 安全 |
| 🟡 StrategySelector 边界 | prompt.length=200/201 的精确边界 | Med | 边界值测试 |
| 🟡 StrategySelector 空 prompt | prompt="" 或 null | Med | 防御性：null → key 不存在 |
| 🟡 TokenBudget 零额度 | `TokenBudget.of(0, 10)` | Med | 边缘情况：立即 exhausted |
| 🟡 FastPathStrategy 多步骤 | FastPath 是否可能输出多个 LLM_INFERENCE | Low | 目前设计只生成 1 个 |

---

## 5. Traceability to Source

| Source File | Line | Related Test |
|-------------|------|-------------|
| `StrategySelector.java:92-116` | `defaultComplexityCheck()` | FP-T02~FP-T06 |
| `FastPathStrategy.java:30-49` | `decide()` | FP-T07, FP-T08 |
| `Plan.java` | Builder + factory | FP-T01 |
| `TokenBudget.java` | consume / isExhausted | FP-T09, FP-T10 |
| `PlannerService.java:45-62` | `plan(Map)` | FP-T11, FP-T12 |

---

## 6. How to Run

```bash
# Run all planner tests
./gradlew :alice-core-planner:test --tests "org.cland.alice.core.planner.PlannerServiceSpec"

# Run FastPath-specific tests
./gradlew :alice-core-planner:test --tests "org.cland.alice.core.planner.PlannerServiceSpec.*FastPath*"
./gradlew :alice-core-planner:test --tests "org.cland.alice.core.planner.PlannerServiceSpec.*StrategySelector*"
./gradlew :alice-core-planner:test --tests "org.cland.alice.core.planner.PlannerServiceSpec.*TokenBudget*"

# Run hole tests
python docs/alice-core-planner/e2e/hole_test_planner.py
```
