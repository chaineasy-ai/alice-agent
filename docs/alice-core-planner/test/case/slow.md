---
title: "Test Case — Planner SlowPath Strategy"
summary: "Comprehensive test case specification for SlowPath Strategy (System 2) — StrategySelector complex routing, SlowPathStrategy MCTS plan generation, ThinkingTree tree search, ThinkingNode UCT computation, StaticPlanner SOP matching, and all boundary/edge conditions."
read_when:
  - "implementing or reviewing SlowPath tests in PlannerServiceSpec"
  - "debugging MCTS tree search or ThinkingTree operations"
  - "adding new SlowPath or SOP test coverage"
scope:
  - "alice-core-planner"
  - "docs/alice-core-planner/test/case"
status: "active"
updated: "2026-06-27"
---

# Test Case — SlowPath (System 2)

## 1. Overview

SlowPath is the **System 2 (慢思考)** path of the planner's dual-path decision engine. It handles high-complexity tasks using **MCTS (Monte Carlo Tree Search)**  inside a `ThinkingTree`, generating a multi-step plan through tree search and simulation.

### Decision Flow

```
StrategySelector.select(context)
  │
  ├── defaultComplexityCheck()
  │     ├── prompt.length() > 200  → ✅ SlowPath
  │     ├── keyword match          → ✅ SlowPath
  │     ├── has feedback           → ✅ SlowPath
  │     ├── has error              → ✅ SlowPath
  │     └── else                   → FastPath
  │
  └── SlowPathStrategy.decide()
        ├── result present? → FINISH
        └── else → MCTS tree search
              ├── expand (generates candidate nodes):
              │     ├── LLM_INFERENCE
              │     ├── TOOL_CALL (per available tool)
              │     ├── OBSERVE
              │     └── REVISION (if feedback)
              ├── simulate (heuristic reward)
              ├── backpropagate
              └── bestPath → Plan [...steps..., FINISH]
```

### Coverage Map

```
┌──────────────────────────────────────────────────────┐
│                 SlowPath Test Coverage                │
│                                                      │
│  SL-T01  ThinkingNode: 构建 + UCT 计算               │
│  SL-T02  ThinkingNode: MCTS 操作 (reward/visit)     │
│  SL-T03  ThinkingTree: 构建 + 根节点                 │
│  SL-T04  ThinkingTree: expand 添加子节点             │
│  SL-T05  ThinkingTree: backpropagate 更新父节点      │
│  SL-T06  ThinkingTree: bestPath 最优路径             │
│  SL-T07  SlowPathStrategy: 生成 MCTS Plan           │
│  SL-T08  SlowPathStrategy: result 存在时直接 FINISH  │
│  SL-T09  SopRegistry: 注册 + 匹配模板               │
│  SL-T10  StaticPlanner: 从 SOP 生成 Plan            │
│  SL-T11  PlannerModelSupplier: ModelSupplier 替代性  │
│  SL-T12  ModelSession: Call 桥接 + complete/fail    │
│  SL-T13  ModelCapabilities: alice-model 委托        │
│  SL-T14  PlannerService: 无限迭代熔断 (TODO)        │
└──────────────────────────────────────────────────────┘
```

---

## 2. Test Cases

### SL-T01: ThinkingNode 构建与 UCT 计算

| Field | Value |
|-------|-------|
| **Target** | `ThinkingNode.builder()` / `ThinkingNode.uct()` |
| **Source** | `PlannerServiceSpec` — `"ThinkingNode should be buildable and compute UCT"` |
| **Status** | 🟩 GREEN |

**UCT 计算公式**:

```
UCT(v) = reward/visits + C * sqrt(ln(parentVisits) / visits)
```

**覆盖场景**:

| Scenario | visits | reward | uct(C=√2) | Status |
|----------|--------|--------|------------|--------|
| 未访问节点 | 0 | 0.0 | `Double.MAX_VALUE` | ✅ |
| 已访问节点 | 3 | 2.0 | 计算值 | ✅ |
| parent 为 null | — | — | 不 NPE | ❌ 未覆盖 |
| 根节点 | — | — | isRoot() = true | ✅ (通过 ROOT actionType) |

**节点属性验证**:

```groovy
child.nodeId() > 0                    // 自增 ID
child.actionType() == "LLM_INFERENCE"
child.actionTarget() == "gpt-4o"
child.thought() == "Reasoning step"
child.reward() == 2.0
child.visits() == 3
child.parent() == parent
child.isLeaf()                        // 无子节点
!child.isRoot()                       // 非根
```

### SL-T02: ThinkingNode MCTS 操作

| Field | Value |
|-------|-------|
| **Target** | `addReward()` / `incrementVisits()` / `markExpanded()` |
| **Source** | `PlannerServiceSpec` — `"ThinkingNode should support MCTS operations"` |
| **Input** | reward=0, visits=0 |
| **Expected** | addReward(1.5)→reward=1.5, incrementVisits×2→visits=2, markExpanded→expanded=true |
| **Status** | 🟩 GREEN |

**增量验证**:

```groovy
node.addReward(1.5)
node.incrementVisits()
node.incrementVisits()
node.setObservation("Success")
node.markExpanded()

node.reward() == 1.5
node.visits() == 2
node.observation() == "Success"
node.expanded()
```

### SL-T03: ThinkingTree 构建与根节点

| Field | Value |
|-------|-------|
| **Target** | `ThinkingTree(state)` |
| **Source** | `PlannerServiceSpec` — `"ThinkingTree should be constructable with root state"` |
| **Input** | `[prompt:"Hello", model:"gpt-4o"]` |
| **Expected** | 根节点 actionType="ROOT", nodeCount=1, depth=0 |
| **Status** | 🟩 GREEN |

```groovy
tree.root() != null
tree.root().actionType() == "ROOT"
tree.nodeCount() == 1
tree.depth() == 0
tree.allNodes().size() == 1
```

### SL-T04: ThinkingTree expand 添加子节点

| Field | Value |
|-------|-------|
| **Target** | `ThinkingTree.expand(node, generators)` |
| **Source** | `PlannerServiceSpec` — `"ThinkingTree expand should add child nodes"` |
| **Input** | 2 generators: [LLM_INFERENCE, TOOL_CALL] |
| **Expected** | nodeCount=3 (1 root + 2 children), depth=2 |
| **Status** | 🟩 GREEN |

**expand 操作后的树结构**:

```
        ROOT (state={})
       /    \
LLM_INFERENCE  TOOL_CALL
(gpt-4o)      (search)
```

**验证**:

```groovy
tree.nodeCount() == 3
tree.root().expanded()
tree.getChildren(tree.root()).size() == 2
tree.depth() == 2
```

### SL-T05: ThinkingTree backpropagate 更新祖先

| Field | Value |
|-------|-------|
| **Target** | `ThinkingTree.backpropagate(node, reward)` |
| **Source** | `PlannerServiceSpec` — `"ThinkingTree backpropagate should update ancestors"` |
| **Input** | child reward=2.0, 回传 |
| **Expected** | child.reward=2.0, child.visits=1, root.reward=2.0, root.visits=1 |
| **Status** | 🟩 GREEN |

**奖励传播路径**: child → root (父节点)

```groovy
tree.backpropagate(child, 2.0)

child.reward() == 2.0
child.visits() == 1
tree.root().reward() == 2.0
tree.root().visits() == 1
```

### SL-T06: ThinkingTree bestPath 最优路径

| Field | Value |
|-------|-------|
| **Target** | `ThinkingTree.bestPath()` |
| **Source** | `PlannerServiceSpec` — `"ThinkingTree bestPath should return path from root to leaf"` |
| **Input** | 2 children: LLM_INFERENCE(reward=1.0,visits=5) vs TOOL_CALL(reward=0.5,visits=3) |
| **Expected** | path[0].isRoot(), path.size() ≥ 1 |
| **Status** | 🟩 GREEN |

**选择逻辑**:
- UCT 公式比较各子节点
- 最优 child → 递归向下（如果已展开）
- 返回 root→...→leaf 路径

**注意**: 当前测试仅验证路径格式（有根节点），未验证选中的是否是真正最优的子节点。

### SL-T07: SlowPathStrategy 生成 MCTS Plan

| Field | Value |
|-------|-------|
| **Target** | `SlowPathStrategy.decide(Map)` |
| **Source** | `PlannerServiceSpec` — `"SlowPathStrategy should generate MCTS plan"` |
| **Input** | `[prompt:"Complex multi-step analysis task"]` |
| **Expected** | plan.type() == SLOW_PATH, metadata 含 treeNodes > 0, path="slow" |
| **Status** | 🟩 GREEN |

**完整调用链路**:

```
SlowPathStrategy.decide([prompt:"Complex multi-step analysis task"])
  → result 不存在
  → ThinkingTree 已由 builder 注入
  → rootState = {prompt: "Complex multi-step analysis task"}
  → runMcts(rootState)
      ├── expander: 生成候选节点
      │     ├── LLM_INFERENCE (reasoning model)
      │     ├── TOOL_CALL × N (per availableTools)
      │     ├── OBSERVE
      │     └── REVISION (if feedback)
      ├── simulator: heuristic reward (prompt length / 100)
      └── mctsIterations = 5 (builder 设置)
  → bestPath → Plan [...steps..., FINISH]
```

**Plan 验证**:

```groovy
plan.type() == Plan.Type.SLOW_PATH
plan.steps().size() >= 1
plan.metadata()["path"] == "slow"
(int) plan.metadata()["treeNodes"] > 0
```

### SL-T08: SlowPathStrategy result 存在时直接 FINISH

| Field | Value |
|-------|-------|
| **Target** | `SlowPathStrategy.decide(Map)` |
| **Source** | `PlannerServiceSpec` — 隐式覆盖（FastPath 的 result 检查逻辑也存在于 SlowPathStrategy） |
| **Input** | `[prompt:"test", result:"done"]` |
| **Expected** | plan.steps()[0].actionType() == "FINISH" |
| **Status** | 🟩 GREEN |

### SL-T09: SopRegistry 注册与匹配模板

| Field | Value |
|-------|-------|
| **Target** | `SopRegistry.register()` / `SopRegistry.match()` |
| **Source** | `PlannerServiceSpec` — `"SopRegistry should register and match templates"` |
| **Status** | 🟩 GREEN |

**模板结构**:

```groovy
def template = SopRegistry.SopTemplate.builder()
    .id("weather_query")
    .description("Get weather information")
    .keywords(["weather", "temperature", "forecast"])
    .addStep("TOOL_CALL", "get_weather")
    .addStep("LLM_INFERENCE", "gpt-4o-mini")
    .build()
```

**匹配覆盖**:

| Scenario | Input | Expected | Status |
|----------|-------|----------|--------|
| 精确关键词匹配 | "What is the weather today?" | 匹配 weather_query | ✅ |
| 部分匹配 | "temperature in Beijing" | 匹配 weather_query | ✅ (最后一个关键词命中) |
| 无匹配 | "hello" | null | ❌ 未覆盖 |
| 多模板匹配 | 注册 2 个模板，输入命中两者 | 返回第一个匹配 | ❌ 未覆盖 |
| 空关键词的模板 | keywords=[] | 永不匹配 | ❌ 未覆盖 |

### SL-T10: StaticPlanner 从 SOP 生成 Plan

| Field | Value |
|-------|-------|
| **Target** | `StaticPlanner.plan(Map)` |
| **Source** | `PlannerServiceSpec` — `"StaticPlanner should generate plan from SOP"` |
| **Status** | 🟩 GREEN |

**Plan 结构**:

```groovy
plan.type() == Plan.Type.STATIC
plan.steps().size() == 3        // 2 templates steps + auto FINISH
plan.steps()[0].actionType() == "TOOL_CALL"
plan.steps()[0].target() == "search_web"
plan.metadata()["sopId"] == "search_workflow"
```

**覆盖场景**:

| Scenario | SOP Keywords | Input | Expected | Status |
|----------|-------------|-------|----------|--------|
| 命中模板 | ["search","find","lookup"] | "Please search for documents" | STATIC Plan | ✅ |
| 未命中 | 同上 | "hello" | null | ❌ 未覆盖 |
| 空 registry | 无模板 | "search" | null | ❌ 未覆盖 |

### SL-T11: PlannerModelSupplier ModelSupplier 替代性

| Field | Value |
|-------|-------|
| **Target** | `PlannerModelSupplier` 接口 |
| **Source** | `PlannerServiceSpec` — `"PlannerModelSupplier should be substitutable as ModelSupplier"` |
| **Status** | 🟩 GREEN |

**Liskov 替代性验证**:

```groovy
supplier instanceof org.cland.alice.model.ModelSupplier    // 编译期契约
supplier instanceof PlannerModelSupplier
```

**接口方法**:

| Method | Return | Description |
|--------|--------|-------------|
| `getReasoningModel()` | `ModelSession` | 高性能推理模型 (Slow Path) |
| `getInstructionModel()` | `ModelSession` | 轻量指令模型 (Fast Path) |
| `request(Call)` | `Call.Response` | (继承自 ModelSupplier) 执行模型调用 |

### SL-T12: ModelSession alice-model 桥接

| Field | Value |
|-------|-------|
| **Target** | `ModelSession` 工厂方法 + complete/fail |
| **Source** | `PlannerServiceSpec` — `"ModelSession should wrap Call with correct payload"`, `"ModelSession complete should transition to FINISHED"`, `"ModelSession fail should transition to ABORTED"` |
| **Status** | 🟩 GREEN |

**核心桥接验证**:

```groovy
// 构建
ModelSession session = ModelSession.of("gpt-4o", "Hello world", [temp: 0.7])
session.modelId() == "gpt-4o"
session.prompt() == "Hello world"
session.call().payload().modelId() == "gpt-4o"     // 委托到 alice-model Call

// complete → FINISHED
session.complete("Final answer")
session.completed()
session.response() == "Final answer"
session.call().status() == CallStatus.FINISHED

// fail → ABORTED
session.fail(new RuntimeException("timeout"))
session.completed()
session.call().status() == CallStatus.ABORTED
```

**Builder 链式调用**:

```groovy
ModelSession.builder()
    .modelId("gpt-4o-mini")
    .prompt("test")
    .parameters([maxTokens: 100])
    .build()
```

### SL-T13: ModelCapabilities alice-model 委托

| Field | Value |
|-------|-------|
| **Target** | `ModelCapabilities` 枚举 |
| **Source** | `PlannerServiceSpec` — `"ModelCapabilities should delegate to Model.Capability"`, `"ModelCapabilities supportsFunctionCall and supportsStreaming"`, `"ModelCapabilities fromCapability should convert correctly"` |
| **Status** | 🟩 GREEN |

**枚举值与 alice-model 的映射**:

| ModelCapabilities | Model.Capability 委托 | function_call | streaming | vision |
|-------------------|----------------------|---------------|-----------|--------|
| `NONE` | `Model.Capability.NONE` | ❌ | ❌ | ❌ |
| `FUNCTION_CALL` | `Model.Capability.FUNCTION_CALL` | ✅ | ❌ | ❌ |
| `STREAMING` | `Model.Capability.STREAMING` | ❌ | ✅ | ❌ |
| `VISION` | `Model.Capability.VISION` | ❌ | ❌ | ✅ |
| `ALL` | `Model.Capability.ALL` | ✅ | ✅ | ✅ |

**转换函数**: `ModelCapabilities.fromCapability(null) == NONE`

### SL-T14: PlannerService 无限迭代熔断 (TODO)

| Field | Value |
|-------|-------|
| **Target** | `PlannerService.plan(Map)` — 熔断机制 |
| **Status** | 🟡 NOT YET (gap) |

SlowPath 的 MCTS 搜索在没有熔断的情况下可能陷入无限循环或长时间搜索。需要覆盖：

| Scenario | Expected | Status |
|----------|----------|--------|
| MCTS 超过 maxIterations 后熔断 | 返回当前 bestPath | ❌ 未覆盖 |
| TokenBudget 耗尽后熔断 | 返回当前 bestPath | ❌ 未覆盖 |
| 模拟阶段超时 | 优雅降级 | ❌ 未覆盖 |

---

## 3. Test Data Matrix

| Test | Fixture | Input | Expected | Key Assertion |
|------|---------|-------|----------|---------------|
| SL-T01 | ThinkingNode builder | S/A/V with parent | valid node | UCT=MAX_VALUE for unvisited |
| SL-T02 | ThinkingNode ops | addReward + increment | updated state | reward=1.5, visits=2 |
| SL-T03 | ThinkingTree | state Map | tree with root | actionType="ROOT", depth=0 |
| SL-T04 | ThinkingTree.expand | 2 generators | 3 nodes | depth=2, children=2 |
| SL-T05 | ThinkingTree.backpropagate | child + reward=2.0 | ancestors updated | root.reward=2.0 |
| SL-T06 | ThinkingTree.bestPath | tree with 2 children | path from root | path[0].isRoot() |
| SL-T07 | SlowPathStrategy | "Complex multi-step analysis task" | SLOW_PATH Plan | treeNodes > 0 |
| SL-T08 | SlowPathStrategy | result:"done" | FINISH step | steps[0]="FINISH" |
| SL-T09 | SopRegistry | keyword match | matched template | id="weather_query" |
| SL-T10 | StaticPlanner | "search for documents" | STATIC Plan | sopId matched |
| SL-T11 | PlannerModelSupplier | instanceof check | is ModelSupplier | compiler contract |
| SL-T12 | ModelSession | of + complete/fail | wrapped Call | status transitions |
| SL-T13 | ModelCapabilities | 5 enum values | delegation chain | supportsFunctionCall etc. |
| SL-T14 | MCTS 熔断 | 超限 | bestPath fallback | ❌ TODO |

---

## 4. Coverage Gaps

| Gap | Description | Priority | Suggestion |
|-----|-------------|----------|------------|
| 🟡 ThinkingNode `parent=null` | UCT 计算方法需要 parentVisits，parent=null 时的防御 | Low | 添加 parent 非空断言 |
| 🟡 SopRegistry 无匹配 | 输入不匹配任何模板时返回 null | Med | 添加测试 + 文档 |
| 🟡 SopRegistry 多模板优先级 | 多个模板同时匹配时选择策略 | Med | 指定优先级字段或返回排序结果 |
| 🟡 StaticPlanner 未命中 | 走不到 StaticPlanner 的 fallback 路径 | Med | 添加 null plan 处理测试 |
| 🟥 MCTS 熔断 | SlowPathStrategy 无迭代超时保护 | **High** | 添加 maxIterations + TokenBudget 双重熔断 |
| 🟡 bestPath 准确性 | 当前只验证 path 格式，未验证选中的是真正最优子节点 | Med | 添加确定性树结构验证 bestPath 结果 |
| 🟡 SlowPathStrategy availableTools | MCTS expander 检查 context 中的 availableTools 列表 | Med | 测试有 tools 时 TOOL_CALL 候选生成 |
| 🟡 SlowPathStrategy 多轮 MCTS | runMcts 中 expander 和 simulator 的协作正确性 | Low | 验证 expand 后节点连接到树中 |
| 🟡 ThinkingTree 序列化 | 支持 alice-memory-vault 持久化（DESIGN 文档要求） | Low | 添加 serialize/deserialize 测试 |

---

## 5. Traceability to Source

| Source File | Line | Related Test |
|-------------|------|-------------|
| `SlowPathStrategy.java:46-110` | `decide()` + `runMcts()` | SL-T07, SL-T08, SL-T14 |
| `ThinkingTree.java` | expand / backpropagate / bestPath | SL-T03~SL-T06 |
| `ThinkingNode.java` | builder / uct / addReward / incrementVisits | SL-T01, SL-T02 |
| `SopRegistry.java` | register / get / match | SL-T09 |
| `StaticPlanner.java` | plan(Map) | SL-T10 |
| `PlannerModelSupplier.java` | interface + bridge | SL-T11 |
| `ModelSession.java` | of / complete / fail / builder | SL-T12 |
| `ModelCapabilities.java` | enum + fromCapability | SL-T13 |
| `StrategySelector.java:92-116` | `defaultComplexityCheck()` — SlowPath 路由 | FP-T03~FP-T05 |
| `PlannerService.java:45-62` | `plan(Map)` — 整体入口 | SL-T07 (集成) |

---

## 6. SlowPath MCTS 树可视化

以输入 `"Complex multi-step analysis task"` 为例，MCTS 搜索后的 ThinkingTree 结构：

```
                    ROOT (prompt="Complex multi-step...")
                   /    |         |         \
                  /     |         |          \
     LLM_INFERENCE    TOOL_CALL  OBSERVE   REVISION
     (gpt-4o,         (read_file, (ENV,      (feedback,
      reward=2.3)      r=1.8)      r=0.5)     if present)
          │
     TOOL_CALL
     (list_dir,
      reward=1.9)
```

**UCT 选择**：在父节点展开后，每一轮 MCTS iteration：
1. **Selection**: UCT 公式选择最有潜力的子节点
2. **Expansion**: 如果叶节点未展开，生成候选子节点
3. **Simulation**: 启发式评估奖励
4. **Backpropagation**: 奖励回传到根节点

---

## 7. How to Run

```bash
# Run all planner tests
./gradlew :alice-core-planner:test --tests "org.cland.alice.core.planner.PlannerServiceSpec"

# Run SlowPath-specific tests
./gradlew :alice-core-planner:test --tests "org.cland.alice.core.planner.PlannerServiceSpec.*SlowPath*"
./gradlew :alice-core-planner:test --tests "org.cland.alice.core.planner.PlannerServiceSpec.*ThinkingTree*"
./gradlew :alice-core-planner:test --tests "org.cland.alice.core.planner.PlannerServiceSpec.*ThinkingNode*"
./gradlew :alice-core-planner:test --tests "org.cland.alice.core.planner.PlannerServiceSpec.*StrategySelector*"
./gradlew :alice-core-planner:test --tests "org.cland.alice.core.planner.PlannerServiceSpec.*SopRegistry*"
./gradlew :alice-core-planner:test --tests "org.cland.alice.core.planner.PlannerServiceSpec.*StaticPlanner*"
./gradlew :alice-core-planner:test --tests "org.cland.alice.core.planner.PlannerServiceSpec.*ModelSession*"
./gradlew :alice-core-planner:test --tests "org.cland.alice.core.planner.PlannerServiceSpec.*ModelCapabilities*"

# Run hole tests
python docs/alice-core-planner/e2e/hole_test_planner.py
```
