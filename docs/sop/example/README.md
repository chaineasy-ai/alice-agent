# SOP Graph — DAG 示例与最佳实践

> SOP (Standard Operating Procedure) 图是基于 **JGrapht** 的有向无环图 (DAG)，
> 用于建模多步骤工作流中的执行顺序、条件分支和并行任务。
> 与传统的平铺步骤列表 (`SopRegistry.SopTemplate`) 不同，SOP 图支持更丰富的控制流。

## 目录

- [核心概念](#核心概念)
- [快速开始：在代码中构建 DAG](#快速开始在代码中构建-dag)
- [GraphML 持久化](#graphml-持久化)
- [示例：天气查询工作流](#示例天气查询工作流)
- [与 SopRegistry 集成](#与-sopregistry-集成)
- [最佳实践](#最佳实践)

---

## 核心概念

| 概念 | 类 | 说明 |
|------|-----|------|
| **图** | `SopGraph` | 整个 SOP 工作流的 DAG 容器，包含节点和边 |
| **节点** | `SopGraph.SopNode` | 工作流中的一个步骤（`actionType` + `target` + `parameters`） |
| **边** | `SopGraph.SopEdge` | 步骤间的流转关系，携带标签（`on-success` / `on-failure` / `condition:<expr>`） |
| **序列化** | `SopGraphPersistence` | GraphML 格式的导入/导出 |

**边标签约定：**

| 标签 | 含义 |
|------|------|
| `on-success` | 上一步执行成功后流转 |
| `on-failure` | 上一步执行失败后流转（错误处理路径） |
| `on-success:parallel` | 上一步成功后，多个目标步骤可并行执行 |
| `condition:<expr>` | 条件表达式满足时流转（如 `condition:temperature > 30`） |

---

## 快速开始：在代码中构建 DAG

### 线性工作流

```java
SopGraph graph = SopGraph.builder("my-sop", "我的工作流")
    .addNode("start", "LLM_INFERENCE", "parse_input")
    .addNode("process", "TOOL_CALL", "process_data")
    .addNode("end", "FINISH", "FINISH")
    .addEdge("start", "process", "on-success")
    .addEdge("process", "end", "on-success")
    .addKeyword("示例")
    .build();
```

### 带参数节点的 DAG

```java
SopGraph graph = SopGraph.builder("search-sop", "搜索工作流")
    .addNode("query", "LLM_INFERENCE", "parse_query", Map.of("model", "gpt-4o"))
    .addNode("search", "TOOL_CALL", "web_search", Map.of("engine", "bing", "max_results", "5"))
    .addNode("summarize", "LLM_INFERENCE", "summarize_results")
    .addNode("finish", "FINISH", "FINISH")
    .addEdge("query", "search")
    .addEdge("search", "summarize", "on-success")
    .addEdge("summarize", "finish", "on-success")
    .build();
```

### 带条件分支和并行任务的 DAG

```java
SopGraph graph = SopGraph.builder("code-review", "代码审查流程")
    .addNode("lint", "TOOL_CALL", "run_linter")
    .addNode("analyze", "TOOL_CALL", "static_analysis")
    .addNode("review", "LLM_INFERENCE", "ai_code_review")
    .addNode("fix", "TOOL_CALL", "auto_fix")
    .addNode("report", "LLM_INFERENCE", "generate_report")
    .addNode("finish", "FINISH", "FINISH")
    // 并行：lint + analyze 同时进行
    .addEdge("lint", "review", "on-success")
    .addEdge("analyze", "review", "on-success:parallel")
    // 条件分支：review 结果决定下一步
    .addEdge("review", "fix", "condition:issues_found")
    .addEdge("review", "report", "on-success")
    .addEdge("fix", "report", "on-success")
    .addEdge("report", "finish", "on-success")
    .build();
```

---

## GraphML 持久化

状态机始终在内存中运行（JGrapht 原生图结构），GraphML 用于保存/恢复。

### 默认存储路径：`~/.alice/sops/`

所有 SOP 图默认存储到 `~/.alice/sops/<id>.graphml`。这个目录会自动创建。

```java
// 保存到默认路径 ~/.alice/sops/<id>.graphml
SopGraphPersistence.save(graph);   // → ~/.alice/sops/weather.graphml

// 从默认路径加载
SopGraph graph = SopGraphPersistence.load("weather");
```

### 自定义文件路径

```java
// 构建 DAG 后保存到自定义路径
SopGraphPersistence.save(graph, new File("sops/my-workflow.graphml"));

// 从自定义路径加载
SopGraph graph = SopGraphPersistence.load(new File("sops/my-workflow.graphml"));

// 或导出为 XML 字符串
String xml = SopGraphPersistence.toXml(graph);

// 从 XML 字符串恢复
SopGraph graph = SopGraphPersistence.fromXml(xml);
```

### 管理已存储的 SOP

```java
// 列出所有已存储的 SOP ID
List<String> ids = SopGraphPersistence.list();

// 删除某个 SOP
SopGraphPersistence.delete("weather");

// 更改默认存储目录
SopGraphPersistence.setDefaultDir(Path.of("/custom/path/sops"));
Path current = SopGraphPersistence.getDefaultDir();
```

### 从平铺步骤列表升级

```java
List<Plan.Step> steps = List.of(
    Plan.Step.of("TOOL_CALL", "step_a"),
    Plan.Step.of("TOOL_CALL", "step_b"),
    Plan.Step.of("FINISH", "FINISH")
);

// 自动生成线性 DAG（step-0 → step-1 → step-2）
SopGraph graph = SopGraph.fromSteps("linear-sop", "线性流程", steps);
```

---

## 示例：天气查询工作流

文件：[weather-sop.graphml](./weather-sop.graphml)

这个示例展示了包含 **并行分支** 和 **聚合** 的典型 SOP DAG：

```
                    ┌──────────────┐
                    │  parse_query │  LLM_INFERENCE: 解析用户查询
                    └──────┬───────┘
                           │ on-success
                           ▼
                    ┌──────────────┐
                    │get_coordinates│ TOOL_CALL: 地理编码
                    └──────┬───────┘
                           │
              ┌────────────┼────────────┐
              │ on-success │            │ on-success:parallel
              ▼            │            ▼
    ┌─────────────────┐   │   ┌────────────────┐
    │  fetch_weather   │   │   │   fetch_aqi    │  ← 并行执行
    └────────┬────────┘   │   └───────┬────────┘
             │            │           │
             └─────┬──────┘           │
                   │ on-success       │ on-success
                   ▼                  │
            ┌─────────────────┐       │
            │ format_response  │ ◄────┘  聚合
            └────────┬────────┘
                     │ on-success
                     ▼
              ┌──────────────┐
              │    finish    │
              └──────────────┘
```

### 加载并执行示例

```java
// 从 example 目录加载
SopGraph weatherSop = SopGraphPersistence.load(
    new File("docs/sop/example/weather-sop.graphml"));

System.out.println("SOP: " + weatherSop.id());           // "weather-sop"
System.out.println("Nodes: " + weatherSop.nodes().size()); // 6
System.out.println("Edges: " + weatherSop.edges().size()); // 6

// 拓扑排序 — 确定执行顺序
for (SopGraph.SopNode node : weatherSop.topologicalOrder()) {
    System.out.println("  " + node.actionType() + " → " + node.target());
}

// 查找根节点和叶节点
List<SopGraph.SopNode> roots = weatherSop.rootNodes();   // [parse_query]
List<SopGraph.SopNode> leaves = weatherSop.leafNodes();  // [finish]

// 某节点的后继
SopGraph.SopNode coords = weatherSop.getNode("get_coordinates");
List<SopGraph.SopNode> next = weatherSop.successorsOf(coords);
// → [fetch_weather, fetch_aqi]  两个并行分支
```

---

## 与 SopRegistry 集成

`SopRegistry` 同时支持传统模板和 DAG 图：

```java
SopRegistry registry = new SopRegistry();

// 方式 A：直接注册 SopGraph（自动同步生成平铺模板）
registry.register(graph);
SopTemplate template = registry.get("weather-sop");
SopGraph fromRegistry = registry.getGraph("weather-sop");

// 方式 B：从 DAG 转为模板（兼容 StaticPlanner）
SopTemplate tpl = graph.toTemplate();
StaticPlanner planner = new StaticPlanner(registry);
Plan plan = planner.plan(Map.of("prompt", "今天北京天气如何？"));
// → 匹配 weather-sop，生成 Plan.Type.STATIC
```

---

## 最佳实践

### 1. 节点 ID 命名

使用有意义的短 ID（`kebab-case`），便于调试和 GraphML 阅读：

```java
// ✅ 推荐
"parse_query", "get_weather", "format_response"

// ❌ 避免
"step_0", "node_1", "n2"
```

### 2. 边标签语义

| 场景 | 标签 |
|------|------|
| 正常流转 | `"on-success"` |
| 错误处理 | `"on-failure"` |
| 并行分支 | `"on-success:parallel"` |
| 条件分支 | `"condition:<表达式>"` |

### 3. 关键词与 SOP 匹配

关键词用于 `SopRegistry.match()` 实现语义搜索。推荐：
- 包含业务领域的核心术语（如 `"天气"`, `"搜索"`, `"代码审查"`）
- 包含同义词（如 `"天气"` 和 `"weather"`）
- 关键词不区分大小写

### 4. GraphML 文件管理

- 每个 `.graphml` 文件对应一个 SOP
- 文件名建议使用 `{sop-id}.graphml` 格式
- 可以按目录组织：`sops/weather/`, `sops/search/`, `sops/code-review/`

### 5. 从平铺列表到 DAG 的渐进迁移

现有 `SopRegistry.SopTemplate` 的平铺步骤列表可以逐步迁移：

```java
// 现有代码（平铺列表）
registry.register(SopTemplate.builder()
    .id("my-sop")
    .addStep("TOOL_CALL", "a")
    .addStep("TOOL_CALL", "b")
    .build());

// 迁移到 DAG（自动生成线性图，与模板兼容）
SopGraph graph = SopGraph.fromSteps("my-sop", "描述", template.steps());
registry.register(graph);  // 既注册了图，也同步了模板
```

---

## API 速查

| 方法 | 说明 |
|------|------|
| `SopGraph.builder(id, description)` | 创建 DAG 构建器 |
| `.addNode(id, actionType, target)` | 添加步骤节点 |
| `.addNode(id, actionType, target, params)` | 添加带参数节点 |
| `.addNode(id, actionType, target, params, thought)` | 添加带参数+思考的节点 |
| `.addEdge(from, to)` | 添加无条件边（默认 `on-success`） |
| `.addEdge(from, to, label)` | 添加条件边 |
| `.addKeyword(kw)` | 添加关键词 |
| `.build()` | 构建 `SopGraph` |
| `graph.topologicalOrder()` | 拓扑排序结果 |
| `graph.toStepList()` | 转为平铺 `Plan.Step` 列表 |
| `graph.toTemplate()` | 转为 `SopTemplate`（兼容 StaticPlanner） |
| `graph.delegate()` | 底层 JGrapht `Graph` 对象 |
| `SopGraphPersistence.save(graph)` | 保存到 `~/.alice/sops/<id>.graphml` |
| `SopGraphPersistence.save(graph, file)` | 保存为 GraphML 到自定义路径 |
| `SopGraphPersistence.load(id)` | 从 `~/.alice/sops/<id>.graphml` 加载 |
| `SopGraphPersistence.load(file)` | 从 GraphML 文件加载 |
| `SopGraphPersistence.toXml(graph)` | 导出 XML 字符串 |
| `SopGraphPersistence.fromXml(xml)` | 从 XML 字符串导入 |
| `SopGraphPersistence.list()` | 列出默认目录下所有 SOP ID |
| `SopGraphPersistence.delete(id)` | 从默认目录删除 SOP |
| `SopGraphPersistence.setDefaultDir(path)` | 更改默认存储目录 |
| `SopGraphPersistence.getDefaultDir()` | 获取当前存储目录 |
| `SopGraph.fromSteps(id, desc, steps)` | 从平铺步骤创建线性 DAG |
