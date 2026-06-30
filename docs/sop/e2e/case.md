---
title: "SOP E2E — End-to-End Test Case"
summary: "Full lifecycle test: build DAG → persist → load → match → plan"
status: "active"
updated: "2026-06-30"
---

# SOP End-to-End Test Case

> Tests the complete SOP lifecycle through `alice-memory-vault`'s `org.cland.alice.memory.sop` package:
> **Build DAG → GraphML persist (`~/.alice/sops/`) → Load → SopRegistry match → StaticPlanner → Plan**

## Prerequisites

- `~/.alice/sops/` directory exists
- `docs/sop/example/weather-sop.graphml` is copied into it

```bash
mkdir -p ~/.alice/sops
cp docs/sop/example/weather-sop.graphml ~/.alice/sops/
```

## Test Script

Run via Gradle hole test:

```bash
./gradlew :alice-memory-vault:runHoleTest --args="sop"
```

Or standalone via the memory-vault module:

```bash
./gradlew :alice-memory-vault:compileJava
# Write and run your own test (see example below)
```

---

## E2E Case: Weather Query SOP

### Phase 1 — Build DAG

```java
SopGraph graph = SopGraph.builder("weather-sop", "天气查询流程")
    .addNode("parse",     "LLM_INFERENCE", "parse_user_query")
    .addNode("coords",    "TOOL_CALL",     "geocode",       Map.of("source", "openstreetmap"))
    .addNode("weather",   "TOOL_CALL",     "weather_api")
    .addNode("aqi",       "TOOL_CALL",     "air_quality_api")
    .addNode("format",    "LLM_INFERENCE", "format_response", Map.of("format", "markdown"))
    .addNode("finish",    "FINISH",        "FINISH")
    .addEdge("parse",  "coords",  "on-success")
    .addEdge("coords", "weather", "on-success")
    .addEdge("coords", "aqi",     "on-success:parallel")
    .addEdge("weather","format",  "on-success")
    .addEdge("aqi",    "format",  "on-success")
    .addEdge("format", "finish",  "on-success")
    .addKeyword("天气").addKeyword("weather").addKeyword("气温")
    .addKeyword("降雨").addKeyword("查询")
    .build();
```

**Expected:**

| Assertion | Value |
|-----------|-------|
| `graph.id()` | `"weather-sop"` |
| `graph.nodes().size()` | `6` |
| `graph.edges().size()` | `6` |
| `graph.keywords().size()` | `5` |
| `graph.rootNodes().size()` | `1` (parse) |
| `graph.leafNodes().size()` | `1` (finish) |
| `graph.hasRoots()` | `true` |
| `graph.getNode("parse").actionType()` | `"LLM_INFERENCE"` |

### Phase 2 — Topological Order

```java
List<SopGraph.SopNode> ordered = graph.topologicalOrder();
```

**Expected order** (edges guarantee this):

```
parse → coords → weather → aqi → format → finish
```

Note: `weather` and `aqi` are at the same topological level (parallel branches).

| Assertion | Value |
|-----------|-------|
| `ordered.size()` | `6` |
| `ordered.get(0).id()` | `"parse"` |
| `ordered.get(5).id()` | `"finish"` |
| `graph.successorsOf(graph.getNode("coords")).size()` | `2` (weather + aqi) |

### Phase 3 — Persist to `~/.alice/sops/`

```java
// Save (default dir: ~/.alice/sops/<id>.graphml)
SopGraphPersistence.save(graph);

// Verify file exists
File saved = SopGraphPersistence.getDefaultDir()
    .resolve("weather-sop.graphml").toFile();
assert saved.exists() : "GraphML file not saved";

// Export to XML string
String xml = SopGraphPersistence.toXml(graph);
System.out.println(xml);
```

**Verify GraphML output contains:**

```xml
<key id="key0" for="node" attr.name="actionType" attr.type="string"/>
<key id="key4" for="edge" attr.name="edgeLabel" attr.type="string"/>
<key id="key5" for="node" attr.name="sopMeta" attr.type="string"/>
...
<node id="parse">
    <data key="key5">weather-sop|天气查询流程|天气,weather,气温,降雨,查询</data>
    <data key="key0">LLM_INFERENCE</data>
    <data key="key1">parse_user_query</data>
</node>
...
<edge source="weather" target="format">
    <data key="key4">on-success</data>
</edge>
```

### Phase 4 — Load from `~/.alice/sops/`

```java
// Load by ID (from default dir)
SopGraph loaded = SopGraphPersistence.load("weather-sop");

// Or load from file
SopGraph fromFile = SopGraphPersistence.load(
    new File(System.getProperty("user.home") + "/.alice/sops/weather-sop.graphml"));

// Or load from XML string
SopGraph fromXml = SopGraphPersistence.fromXml(xml);
```

**Expected:**

| Assertion | `loaded` | `fromFile` | `fromXml` |
|-----------|----------|------------|-----------|
| `.id()` | `"weather-sop"` | `"weather-sop"` | `"weather-sop"` |
| `.nodes().size()` | `6` | `6` | `6` |
| `.edges().size()` | `6` | `6` | `6` |
| `.keywords().size()` | `5` | `5` | `5` |
| `.getNode("parse").actionType()` | `"LLM_INFERENCE"` | same | same |
| `.getNode("coords").target()` | `"geocode"` | same | same |

### Phase 5 — SopRegistry Match

```java
SopRegistry registry = new SopRegistry();
registry.register(loaded);

// Keyword match
SopRegistry.SopTemplate matched = registry.match("今天北京天气如何？");
assert matched != null : "No SOP matched";
assert "weather-sop".equals(matched.id()) : "Wrong SOP matched";

// Step structure preserved
assert matched.steps().size() == 6;
assert matched.steps().get(2).actionType().equals("TOOL_CALL");
assert matched.steps().get(2).target().equals("weather_api");

// Registry queries
SopGraph fromRegistry = registry.getGraph("weather-sop");
assert fromRegistry != null;
assert registry.ids().contains("weather-sop");
```

### Phase 6 — StaticPlanner → Plan

```java
StaticPlanner planner = new StaticPlanner(registry);

Map<String, Object> ctx = Map.of("prompt", "今天北京气温多少？");
Plan plan = planner.plan(ctx);

assert plan != null : "No plan generated";
assert plan.type() == Plan.Type.STATIC;
assert "weather-sop".equals(plan.metadata().get("sopId"));

// Steps match DAG topology
assert plan.steps().get(0).actionType().equals("LLM_INFERENCE");
assert plan.steps().get(0).target().equals("parse_user_query");
assert plan.steps().get(2).actionType().equals("TOOL_CALL");
assert plan.steps().get(2).target().equals("weather_api");
assert plan.steps().get(5).actionType().equals("FINISH");

// Auto-appended FINISH if missing
assert plan.steps().get(plan.steps().size() - 1).actionType().equals("FINISH");
```

### Phase 7 — PlannerService Integration

```java
// Inject StaticPlanner as a function into PlannerService
StrategySelector selector = StrategySelector.builder()
    .fastPath(ctx -> Plan.fastPath("Fast", "FINISH", "FINISH"))
    .slowPath(ctx -> Plan.builder()
        .type(Plan.Type.SLOW_PATH).summary("Slow").addStep("FINISH", "FINISH").build())
    .build();

PlannerService plannerService = PlannerService.builder()
    .strategySelector(selector)
    .staticPlannerFn(staticPlanner::plan)   // ← SOP injected here
    .build();

// SOP matched → STATIC plan
Plan sopPlan = plannerService.plan(Map.of("prompt", "明天上海的天气"));
assert sopPlan.type() == Plan.Type.STATIC;

// No SOP match → falls through to StrategySelector
Plan fastPlan = plannerService.plan(Map.of("prompt", "hello"));
assert fastPlan.type() == Plan.Type.FAST_PATH;
```

### Phase 8 — Store Management

```java
// List all stored SOPs
List<String> ids = SopGraphPersistence.list();
System.out.println("Stored SOPs: " + ids);

// Delete
SopGraphPersistence.delete("weather-sop");
assert !SopGraphPersistence.getDefaultDir()
    .resolve("weather-sop.graphml").toFile().exists();

// Change default directory
SopGraphPersistence.setDefaultDir(Path.of("/tmp/my-sops"));
Path current = SopGraphPersistence.getDefaultDir();
assert current.toString().equals("/tmp/my-sops");

// Restore
SopGraphPersistence.setDefaultDir(SopGraphPersistence.DEFAULT_SOPS_DIR);
```

---

## Expected Output (full run)

```
=== SOP Store Test: ~/.alice/sops/ ===
Default dir: /home/charlie/.alice/sops
SOPs before: [weather-sop]
Saved: test-hello.graphml
Loaded: test-hello (2 nodes, 1 edges)
SOPs after: [test-hello, weather-sop]
StaticPlanner matched SOP: test-hello
Plan: Plan{type=STATIC, steps=2, summary='Static plan from SOP: test-hello'}
Loaded weather-sop: 6 nodes
Deleted: test-hello.graphml
SOPs final: [weather-sop]
=== RESULTS: 15 passed, 0 failed ===
```

---

## Running via Gradle

### Quick verification (pre-built SOP)

```bash
# Copy example SOP
cp docs/sop/example/weather-sop.graphml ~/.alice/sops/

# Run SOP hole test
./gradlew :alice-memory-vault:runHoleTest --args="sop"
```

### Full module verification

```bash
# Run all memory-vault hole tests
./gradlew :alice-memory-vault:runHoleTest --args="all"

# Run all core-planner tests (PlannerService with Function injection)
./gradlew :alice-core-planner:runHoleTest --args="all"

# Full project check
./gradlew check
```

---

## Architecture Summary

```
┌─────────────────────────────────────────────────────────────┐
│                  ~/.alice/sops/*.graphml                     │
│                    (GraphML files on disk)                   │
└────────────────────────┬────────────────────────────────────┘
                         │ SopGraphPersistence.save/load
                         ▼
┌─────────────────────────────────────────────────────────────┐
│              alice-memory-vault (SOP 程序性记忆)              │
│                                                             │
│  SopGraph           ← JGrapht DefaultDirectedGraph          │
│  SopNode / SopEdge    DAG nodes and edges                   │
│  SopRegistry        ← keyword-based matching                │
│  StaticPlanner      ← SopTemplate → Plan 转换               │
└────────────────────────┬────────────────────────────────────┘
                         │ staticPlannerFn (Function)
                         ▼
┌─────────────────────────────────────────────────────────────┐
│              alice-core-planner (PlannerService)             │
│                                                             │
│  1. result present? → FINISH                                │
│  2. staticPlannerFn.apply(ctx)? → STATIC plan               │
│  3. StrategySelector → FAST or SLOW path                   │
└─────────────────────────────────────────────────────────────┘
```
