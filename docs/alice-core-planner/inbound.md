---
title: "alice-core-planner — Inbound Integration"
summary: "Aggregate root, inbound integration points, and consumer map for alice-core-planner module. Describes PlannerService as the facade, exported packages, and how alice-core-agent consumes planner output."
read_when:
  - "understanding how alice-core-agent consumes PlannerService"
  - "adding new consumers of planner API"
  - "reviewing module boundary and exported packages of alice-core-planner"
  - "implementing or debugging PlannerService or StrategySelector wiring"
scope:
  - "alice-core-planner"
  - "alice-core-agent"
status: "active"
updated: "2026-06-20"
---

# alice-core-planner — Inbound Integration

## 1. Aggregation Root: `PlannerService`

`PlannerService` is the **single facade** and **aggregation root** for the core planner module.
It orchestrates a dual-path decision engine (Fast Path / Slow Path) behind a unified API:

```
Consumer (Agent / AgentExecutor)
       │
       ▼
┌──────────────────────────────────────────────────────────┐
│                   PlannerService                          │  ← 唯一入口
│  plan(Map<String, Object> context) → Plan                │
│  plan(String prompt) → Plan                              │
│  plan(String prompt, String modelId) → Plan              │
│                                                          │
│  ┌──────────────────────────────────────────────────┐   │
│  │            StaticPlanner  (SOP 模板匹配)           │   │
│  │  ┌───────────────────────────────────────────┐   │   │
│  │  │   SopRegistry  (标准流程注册表)             │   │   │
│  │  │   - template lookup by keyword / semantic   │   │   │
│  │  └───────────────────────────────────────────┘   │   │
│  └──────────────────────────────────────────────────┘   │
│                                                          │
│  ┌──────────────────────────────────────────────────┐   │
│  │  StrategySelector  (复杂度评估 + 策略路由)        │   │
│  │  ┌─────────────────┐   ┌──────────────────┐    │   │
│  │  │ FastPathStrategy  │   │ SlowPathStrategy  │    │   │
│  │  │ (System 1)        │   │ (System 2, MCTS)  │    │   │
│  │  └─────────────────┘   └──────────────────┘    │   │
│  └──────────────────────────────────────────────────┘   │
│                                                          │
│  Sub-components:                                         │
│  ┌──────────────────────────────────────────────────┐   │
│  │  ModelSupplier    — LLM Agnostic 抽象            │   │
│  │  ModelSession     — 单次模型调用封装              │   │
│  │  ThinkingTree     — MCTS 思维树                  │   │
│  │  ThinkingNode     — 树节点 (S/A/V)              │   │
│  │  TokenBudget      — Token 预算控制               │   │
│  └──────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────┘
```

### 1.1 Module Identity

The module JPMS name is `alice.agent.alice.core.planner.main`.

**Exported packages** (from `module-info.java`):

| Package | Contents | Exported |
|---------|----------|----------|
| `org.cland.alice.core.planner` | `PlannerService`, `Plan`, `Plan.Step`, `Plan.Type` | ✅ yes |
| `org.cland.alice.core.planner.strategy` | `StrategySelector`, `DecisionStrategy`, `FastPathStrategy`, `SlowPathStrategy` | ✅ yes |
| `org.cland.alice.core.planner.tree` | `ThinkingTree`, `ThinkingNode` | ✅ yes |
| `org.cland.alice.core.planner.sop` | `StaticPlanner`, `SopRegistry`, `SopTemplate` | ✅ yes |
| `org.cland.alice.core.planner.budget` | `TokenBudget` | ✅ yes |
| `org.cland.alice.core.planner.model` | `PlannerModelSupplier`, `ModelSession`, `ModelCapabilities`<br/>⚠️ 内部 SPI，不对外导出 | ❌ internal |

> **Note**: The `org.cland.alice.core.planner.model` package is **internal SPI** — not exported via `module-info.java`.
> It is bridged to `alice-model`'s `org.cland.alice.model.ModelSupplier` via `PlannerModelSupplier extends ModelSupplier`.
> `ModelSession` wraps `org.cland.alice.model.Call` internally.
> `ModelCapabilities` delegates to `org.cland.alice.model.Model.Capability`.

### 1.2 Exported Type Summary

| Type | Kind | Public Methods | Role |
|------|------|---------------|------|
| `PlannerService` | `final class` | 3 (plan ×3) + Builder | Aggregate Root |
| `Plan` | `final class` | 7 (5 getters + 2 factory) + 1 toString | Output Value Object |
| `Plan.Step` | `final class` | 5 (actionType, target, parameters, thought, toActionMap) + 1 toString | Step Value Object |
| `DecisionStrategy` | `@FunctionalInterface` | 1 (decide) | Strategy SPI |
| `StrategySelector` | `final class` | 3 (select, fastPath, slowPath) + Builder | Strategy Router |
| `FastPathStrategy` | `final class` | 0 (implements DecisionStrategy) + ctor | Strategy Impl |
| `SlowPathStrategy` | `final class` | 0 (implements DecisionStrategy) + ctor | Strategy Impl |
| `PlannerModelSupplier` | `interface` | 2 abstract (getReasoningModel, getInstructionModel) + extends `ModelSupplier.request(Call)` | SPI Bridge |
| `ModelSession` | `final class` | 2 factory (of) + 7 getter/mutator + Builder — wraps `Call` | Model Call Value Object |
| `ModelCapabilities` | `enum` | 3 (supportsFunctionCall, supportsStreaming, fromCapability) + delegate to `Model.Capability` | Model Capability Tag |
| `ThinkingTree` | `final class` | 15+ (root, expand, evaluate, backpropagate, mctsIteration, serialize, ...) | MCTS Tree |
| `ThinkingNode` | `final class` | 12 (getters + UCB calculation + child management) | MCTS Node |
| `TokenBudget` | `final class` | 12 (2 factory + 5 accessor + 2 mutator + 1 check + 1 reset + 1 toString) | Budget Guard |
| `StaticPlanner` | `final class` | 1 (plan) + 1 ctor | SOP Executor |
| `SopRegistry` | `final class` | 5 (register, get, match, ids, all, clear) + nested SopTemplate | SOP Store |

## 2. Consumer Map

```
alice-core-agent (Agent, AgentExecutor)
  ├── Agent             → holds PlannerService reference (via withPlannerService)
  ├── AgentExecutor     → calls PlannerService.plan(context) for Macro/Micro planning
  └── ReAct             → adapts PlannerService as Reason phase via ReAct.from(planner)

alice-bootstrap (AliceApp, AliceAgent)
  └── constructs PlannerService with StrategySelector, StaticPlanner
      └── injects into Agent via Agent.withPlannerService()
```

### 2.1 `Agent` (core-agent)

`Agent.java` is the **primary holder** of `PlannerService`. It stores the reference and exposes it to the executor:

```java
public class Agent {
    private PlannerService plannerService;

    // Injection point (called by bootstrap during assembly):
    public Agent withPlannerService(PlannerService plannerService) {
        this.plannerService = plannerService;
        return this;
    }

    // Accessor (consumed by AgentExecutor):
    public PlannerService plannerService() { return plannerService; }
}
```

The planner is optional — `AgentExecutor` gracefully degrades to a simple LLM call when `plannerService == null`.

### 2.2 `AgentExecutor` (core-agent)

`AgentExecutor` is the **primary consumer** of `PlannerService`. It uses the planner in two distinct phases:

#### Macro Planning (phase-level goal setting)

```java
// In the macro iteration loop:
Plan plan = agent.plannerService().plan(context.asMap());
// Plan is converted to a first-step intent Map via planToIntent()
```

Trigger: at the start of each macro iteration, before the Micro-ReAct loop.

#### Micro-ReAct (step-level reasoning)

```java
// After each Observe, the planner is used as the Reason phase:
Map<String, Object> microCtx = new HashMap<>(context.asMap());
microCtx.put("last_observation", observation);
microCtx.put("intent", currentIntent);

Plan microPlan = agent.plannerService().plan(microCtx);
```

Trigger: after each Observe in the Micro-ReAct loop, to generate the next action intent.

#### Graceful Degradation

When `plannerService == null` (e.g., during testing or fallback mode):

```java
if (agent.plannerService() == null) {
    // === Reason without PlannerService ===
    // Falls back to direct LLM inference (no dual-path decision)
}
```

### 2.3 `ReAct` (core-agent, lifecycle)

`ReAct` is an interface that provides a static adapter method to wrap `PlannerService` as its Reason phase:

```java
public interface ReAct {
    // Adapter: PlannerService → ReAct Reason phase
    static ReAct from(PlannerService plannerService) {
        return (state, observation) -> {
            Plan plan = plannerService.plan(state);
            // Plan.Step → Action Map
        };
    }
}
```

### 2.4 `AliceApp` / `AliceAgent` (bootstrap)

The bootstrap layer is responsible for **constructing and wiring** `PlannerService`:

```java
// AliceAgent assembly:
SopRegistry sopRegistry = new SopRegistry();
StaticPlanner staticPlanner = new StaticPlanner(sopRegistry);

ModelSupplier modelSupplier = new OpenAiSupplier(...);
FastPathStrategy fastPath = new FastPathStrategy(modelSupplier);
SlowPathStrategy slowPath = new SlowPathStrategy(modelSupplier, worldModel);

StrategySelector selector = StrategySelector.builder()
    .fastPath(fastPath)
    .slowPath(slowPath)
    .build();

PlannerService planner = PlannerService.builder()
    .strategySelector(selector)
    .staticPlanner(staticPlanner)
    .build();

Agent agent = new Agent()
    .withPlannerService(planner)
    .withToolRegistry(toolRegistry)
    ...;
```

## 3. Public API Detail

### 3.1 `PlannerService` — plan methods

| Method | Signature | Description |
|--------|-----------|-------------|
| `plan(Map)` | `Plan plan(Map<String, Object> context)` | Primary API: context Map → Plan |
| `plan(String)` | `Plan plan(String prompt)` | Convenience: prompt → Map → plan |
| `plan(String,String)` | `Plan plan(String prompt, String modelId)` | Convenience: + model ID override |

**Decision flow**:

```
plan(Map<String, Object> context)
  │
  ├── 1. If context has non-null "result" → return FINISH Plan immediately
  │
  ├── 2. If StaticPlanner exists, try SOP template match
  │       ├── Match → return Plan (STATIC type)
  │       └── No match → continue
  │
  └── 3. StrategySelector.select(context)
            ├── Complexity low → FastPathStrategy.decide()
            └── Complexity high → SlowPathStrategy.decide()
                                    └── ThinkingTree.search() (MCTS)
```

### 3.2 `Plan` — output value object

| Field | Type | Description |
|-------|------|-------------|
| `type()` | `Plan.Type` | `FAST_PATH`, `SLOW_PATH`, or `STATIC` |
| `summary()` | `String` | Human-readable summary |
| `steps()` | `List<Plan.Step>` | Ordered step list |
| `metadata()` | `Map<String, Object>` | Extra metadata |

**`Plan.Step`**:

| Field | Type | Description |
|-------|------|-------------|
| `actionType()` | `String` | `LLM_INFERENCE`, `TOOL_CALL`, `FINISH`, `REVISION`, `OBSERVE` |
| `target()` | `String` | Target identifier |
| `parameters()` | `Map<String, Object>` | Step parameters |
| `thought()` | `String` | LLM reasoning text |
| `toActionMap()` | `Map<String, Object>` | Converts to Action-compatible Map |

**Plan factory helpers**:
- `Plan.fastPath(summary, actionType, target)` — quick single-step FAST_PATH
- `Plan.staticPlan(summary, steps)` — STATIC plan with explicit step list

### 3.3 `StrategySelector` — complexity router

| Method | Signature | Description |
|--------|-----------|-------------|
| `select` | `Plan select(Map<String, Object> context)` | Route to fast/slow path |

Default complexity heuristic: prompt length > 200 chars OR keyword match → slow path.

The selector also supports injection of a custom `@FunctionalInterface complexityFunction` for Router-model-based
complexity assessment.

### 3.4 `PlannerModelSupplier` — LLM abstraction SPI bridge

| Method | Signature | Description |
|--------|-----------|-------------|
| `getReasoningModel()` | `ModelSession getReasoningModel()` | Performance model (System 2 / Slow Path) |
| `getInstructionModel()` | `ModelSession getInstructionModel()` | Lightweight model (System 1 / Fast Path) |
| `request(Call)` | `Call.Response request(Call)` **(inherited from `alice-model`)** | Execute model call |

Planner is fully LLM-agnostic — it holds `PlannerModelSupplier`, which extends `alice-model`'s `ModelSupplier`.
`ModelSession` wraps `alice-model`'s `Call` internally for traceability.

## 4. Module Dependency Direction

```
alice-core-agent ──requires──► alice-core-planner ──requires──► alice-model (ONE-WAY chain)
```

- `alice-core-planner` exports 5 packages (model package is internal SPI, not exported).
- `alice-core-planner` **requires** `alice.agent.alice.model.main`:
  - `PlannerModelSupplier` extends `org.cland.alice.model.ModelSupplier`
  - `ModelSession` wraps `org.cland.alice.model.Call`
  - `ModelCapabilities` delegates to `org.cland.alice.model.Model.Capability`
- `alice-core-agent` imports only `PlannerService`, `Plan` from `alice-core-planner` — never touches `alice-model` directly.
- No reverse dependency exists at any level.

## 5. Method Count Control

| Class | Public Methods | Role | Status |
|-------|---------------|------|--------|
| `PlannerService` | 3 (plan ×3) + Builder | Aggregate Root | ✅ 合理 |
| `Plan` | 7 (5 getters + 2 factory) + 1 toString | Output VO | ✅ 合理 |
| `Plan.Step` | 5 (getters + toActionMap) + 1 toString | Step VO | ✅ 合理 |
| `StrategySelector` | 3 (select, fastPath, slowPath) + Builder | Strategy Router | ✅ 合理 |
| `DecisionStrategy` | 1 (decide) | SPI | ✅ 极简 |
| `FastPathStrategy` | 0 (implements `DecisionStrategy`) + ctor | Strategy Impl | ✅ 合理 |
| `SlowPathStrategy` | 0 (implements `DecisionStrategy`) + ctor | Strategy Impl | ✅ 合理 |
| `PlannerModelSupplier` | 2 abstract + extends `ModelSupplier.request(Call)` | SPI Bridge | ✅ 极简 |
| `ModelSession` | 2 factory (of) + 7 getter/mutator + Builder — wraps `Call` | Model Call VO | ✅ 合理 |
| `SopRegistry` | 6 (register, get, match, ids, all, clear) | SOP Store | ✅ 合理 |
| `StaticPlanner` | 1 (plan) + 1 ctor | SOP Executor | ✅ 极简 |

## 6. Related Documents

- [DESIGN.md](./DESIGN.md) — Deep modular design for dual-path decision engine
- [WorldModel.md](./WorldModel.md) — WorldModel as mental simulator for Slow Path
- [e2e/scene-planner-endpoints.md](./e2e/scene-planner-endpoints.md) — E2E hole test scene
- [../alice-core-agent/DESIGN.md](../alice-core-agent/DESIGN.md) — Core agent lifecycle design
- [../alice-core-agent/e2e/scene-executor-endpoints.md](../alice-core-agent/e2e/scene-executor-endpoints.md) — Executor E2E scene (shows planner usage)
