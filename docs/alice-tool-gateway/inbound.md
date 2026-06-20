---
title: "alice-tool-gateway Inbound Ports"
summary: "Inbound ports of the tool gateway — how application layer accesses ToolRegistry through ToolRegistryHolder, and the MCP tool model for external tool registration"
read_when:
  - "understanding how facades (CLI/TUI) connect to the tool gateway"
  - "understanding how env-adapter registers MCP tools into tool-gateway"
  - "implementing or modifying ToolRegistryHolder or inbound access patterns"
  - "debugging tool registration or execution from application layer"
  - "module boundary rules between tool-gateway and env-adapter"
scope:
  - "alice-tool-gateway"
  - "alice-env-adapter"
status: "active"
updated: "2026-06-20"
---

# alice-tool-gateway Inbound Ports

## 1. Overview

The `alice-tool-gateway` module exposes a single inbound entry point — **`ToolRegistryHolder`** — through which the application layer (CLI/TUI facades) and env-adapter access the aggregate root `ToolRegistry`.

```
Facade / env-adapter
       │
       │  ToolRegistryHolder.INSTANCE.registry()
       ▼
┌──────────────────────────────────────────────────────────┐
│                   ToolRegistryHolder                      │  ← 唯一入口
│  ┌───────────────────────────────────────────────────┐  │
│  │               ToolRegistry (聚合根)                │  │
│  │  register() / lookup() / unregister() / ...       │  │
│  │                                                   │  │
│  │  [builtin]  ToolMetadata → targetBean.method()     │  │
│  │  [MCP]      ToolMetadata → McpTool.invoke()       │  │
│  └───────────────────────────────────────────────────┘  │
│                                                         │
│  Domain Services:                                       │
│  ┌───────────────────────────────────────────────────┐  │
│  │  ToolDiscovery  - 扫描 @AgentTool 注解并注册       │  │
│  │  ExecutionEngine - 执行任意 ToolMetadata          │  │
│  └───────────────────────────────────────────────────┘  │
│                                                         │
│  MCP Model:                                             │
│  ┌───────────────────────────────────────────────────┐  │
│  │  McpTool          - MCP 工具模型 + invoke()       │  │
│  │  McpToolAdapter   - McpTool → ToolMetadata        │  │
│  └───────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────┘
```

### 1.1 Module Boundary Rule

> **⚠️ Critical**: `alice-env-adapter` depends on `alice-tool-gateway`, NOT the reverse.

| Direction | Allowed | What crosses the boundary |
|-----------|---------|--------------------------|
| env-adapter → tool-gateway | ✅ | `ToolRegistryHolder`, `McpTool`, `McpToolAdapter`, `Tool`, `ToolResult` |
| tool-gateway → env-adapter | ❌ **FORBIDDEN** | Tool-gateway must NEVER import anything from env-adapter |

**Why it works:**
- `McpTool.ToolInvoker` is a `@FunctionalInterface` defined in `tool-gateway`. `env-adapter` creates an anonymous implementation that calls `McpClient.callTool()`.
- `McpToolAdapter` lives in `tool-gateway`, binds the `McpTool.invoke()` method into `ToolMetadata` via `MethodHandle.bindTo()`.
- Tool-gateway has zero knowledge about MCP transports, SSE, stdio subprocesses, or config files.

## 2. Inbound Entry: `ToolRegistryHolder`

```java
ToolRegistryHolder.INSTANCE.registry()   // → ToolRegistry
ToolRegistryHolder.INSTANCE.allTools()   // → Collection<ToolMetadata>
```

- **全局单例** — 工具注册是全局唯一的（一个 JVM 一个能力目录）
- **应用层不直接 new 聚合根** — `ToolRegistry` 由 `ToolRegistryHolder` 持有并管理生命周期

### 2.1 Usage in Facades

```java
// CLI Facade: scan builtin tools
ToolRegistry tr = ToolRegistryHolder.INSTANCE.registry();
var discovery = new ToolDiscovery(tr);
discovery.scanAndRegister(List.of(new BuiltinTools()));
agent.withToolRegistry(tr);
```

### 2.2 Usage in env-adapter

```java
// env-adapter: create McpTool, convert, register
ToolRegistry registry = ToolRegistryHolder.INSTANCE.registry();

McpTool.ToolInvoker invoker = (params) -> {
    ToolResult r = client.callTool(toolName, params).get(30, SECONDS);
    return r.isError() ? "Error: " + r.error() : r.text();
};

McpTool mcpTool = McpTool.builder()
    .serverId("filesystem")
    .toolName("read")
    .description("Read file contents")
    .inputSchema(schema)
    .invoker(invoker)
    .build();

registry.register(McpToolAdapter.toToolMetadata(mcpTool));
```

> env-adapter 不依赖任何生命周期事件。它通过 `ToolRegistryHolder.INSTANCE.registry()`
> 直接获取 registry 实例并写入。tool-gateway 不知道谁在注册、什么时候注册。

## 3. Aggregate Root: `ToolRegistry`

**7 个 public 方法**:

| Method | Purpose | Called By |
|--------|---------|-----------|
| `register(ToolMetadata)` | 注册工具 (幂等) | `ToolDiscovery`, env-adapter |
| `lookup(String)` | 按名查找 | `ExecutionEngine` |
| `hasTool(String)` | 检查存在性 | CLI `/tool list`, `Agent` |
| `toolNames()` | 获取所有工具名 | CLI `/tool list` |
| `allTools()` | 获取所有元数据 | CLI `/tool list`, `AgentExecutor` |
| `unregister(String)` | 移除工具 | 管理接口 |
| `size()` | 工具数量 | 管理接口 |

## 4. Domain Services

### 4.1 `ToolDiscovery` — 1 个 public 方法

| Method | Purpose |
|--------|---------|
| `scanAndRegister(List<Object>)` | 扫描 `@AgentTool` 注解 Bean, 注册到 `ToolRegistry` |

### 4.2 `ExecutionEngine` — 2 个 public 方法

| Method | Purpose |
|--------|---------|
| `invoke(String, Map)` | 执行工具 (含沙箱选择 + 超时控制) |
| `shutdown()` | 释放线程池 |

`ExecutionEngine` 不区分 builtin/MCP — 都走 `ToolRegistry.lookup()` → `ToolMetadata.invoke()`。

## 5. MCP Tool Model

### 5.1 `McpTool` — 6 个字段 + `invoke(Map)→String`

| Field | Type | Description |
|-------|------|-------------|
| `serverId` | `String` | MCP Server 标识（如 `filesystem`） |
| `toolName` | `String` | MCP Server 上的原始工具名（如 `read`） |
| `qualifiedName()` | `String` | `serverId:toolName`（用作 ToolRegistry key） |
| `description` | `String` | 工具描述 |
| `inputSchema` | `Map<String, Object>` | 工具参数 JSON Schema |
| `invoker` | `ToolInvoker` | 函数接口，env-adapter 实现 |

### 5.2 `ToolInvoker` — 函数接口

```java
@FunctionalInterface
public interface ToolInvoker {
    String invoke(Map<String, Object> params) throws Exception;
}
```

env-adapter 实现此接口来调用对应的 MCP Server。

### 5.3 `McpToolAdapter` — 适配器

```java
ToolMetadata metadata = McpToolAdapter.toToolMetadata(mcpTool);
```

转换逻辑：
- `McpTool.qualifiedName()` → `ToolMetadata.name`
- `McpTool.inputSchema` (Map) → `JsonNode` (inputSchema)
- `McpTool.invoke(Map)` → `MethodHandle.bindTo(mcpTool)` 签名 `(Map)→String`

## 6. Conversion Flow

```
MCP Server tools/list
       │
       ▼
McpClient.parseTools() → List<Tool>
       │
       ▼
env-adapter: 创建 McpTool 对象
       │  .serverId("filesystem")
       │  .toolName("read")
       │  .invoker((params) → client.callTool("read", params))
       │
       ▼
McpToolAdapter.toToolMetadata(mcpTool)
       │  → MethodHandle.bindTo(mcpTool)  // (Map)→String
       │  → ToolMetadata.builder().name("filesystem:read")
       │                  .targetMethod(handle)
       │                  .build()
       │
       ▼
ToolRegistryHolder.INSTANCE.registry().register(metadata)
       │
       ▼
ExecutionEngine.invoke("filesystem:read", {"path":"/tmp"})
       │  → registry.lookup("filesystem:read")           // 同一 ToolRegistry
       │  → metadata.invoke({"path":"/tmp"})             // McpTool.invoke(Map)
       │  → invoker.invoke({"path":"/tmp"})              // 转发到 MCP Server
       │  → McpClient.callTool("read", {"path":"/tmp"})
       ▼
ToolResult{SUCCESS, rawData="file content"}
```

## 7. Method Count Control

| Class | Public Methods | Role | Status |
|-------|---------------|------|--------|
| `ToolRegistry` | 7 | Aggregate Root | ✅ 合理 |
| `ToolDiscovery` | 1 | Domain Service | ✅ 极简 |
| `ExecutionEngine` | 2 | Domain Service | ✅ 极简 |
| `ToolRegistryHolder` | 2 | Inbound Entry | ✅ 极简 |
| `McpTool` | N fields + 1 invoke | Model | ✅ 合理 |
| `McpToolAdapter` | 2 | Adapter | ✅ 极简 |
| `McpTool.ToolInvoker` | 1 | Functional Interface | ✅ 极简 |

## 8. Related Documents

- [DESIGN.md](./DESIGN.md) — Module design overview
- [capability.md](./capability.md) — Tool capability definitions
- [USER_TOOLS.md](./USER_TOOLS.md) — Consumer guide for calling tools
- [META_TOOLS.md](./META_TOOLS.md) — Developer/test documentation for tools
- [mcp-servers.md](../alice-env-adapter/mcp-servers.md) — MCP server configuration (env-adapter docs)
- [e2e/scene-tool-gateway-endpoints.md](./e2e/scene-tool-gateway-endpoints.md) — Hole test scene
