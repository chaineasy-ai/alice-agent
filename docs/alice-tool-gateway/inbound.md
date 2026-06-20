---
title: "alice-tool-gateway Inbound Ports"
summary: "Inbound ports of the tool gateway — how application layer accesses ToolRegistry (aggregate root) and its domain services (ToolDiscovery, ExecutionEngine) through ToolRegistryHolder"
read_when:
  - "understanding how facades (CLI/TUI) connect to the tool gateway"
  - "implementing or modifying ToolRegistryHolder or inbound access patterns"
  - "debugging tool registration or execution from application layer"
scope:
  - "alice-tool-gateway"
status: "active"
updated: "2026-06-20"
---

# alice-tool-gateway Inbound Ports

## 1. Overview

The `alice-tool-gateway` module exposes a single inbound entry point — **`ToolRegistryHolder`** — through which the application layer (CLI/TUI facades) accesses the aggregate root `ToolRegistry` and its domain services.

```
Application Layer (Facade)
       │
       │  inbound
       ▼
┌──────────────────────────────────────────┐
│           ToolRegistryHolder              │  ← 唯一入口
│  ┌────────────────────────────────────┐  │
│  │        ToolRegistry (聚合根)        │  │
│  │  ├── register() / lookup()         │  │
│  │  ├── toolNames() / allTools()      │  │
│  │  └── toFunctionCallingSchema()     │  │
│  └──────────────┬─────────────────────┘  │
│                 │                         │
│  ┌──────────────▼─────────────────────┐  │
│  │     ToolDiscovery (领域服务)        │  │
│  │  └── scanAndRegister()             │  │
│  └──────────────┬─────────────────────┘  │
│                 │                         │
│  ┌──────────────▼─────────────────────┐  │
│  │    ExecutionEngine (领域服务)        │  │
│  │  └── invoke() / shutdown()         │  │
│  └────────────────────────────────────┘  │
└──────────────────────────────────────────┘
```

## 2. Inbound Port: `ToolRegistryHolder`

```java
// 全局单例持有者 — 应用层通过它获取聚合根
ToolRegistryHolder.INSTANCE.registry()   // → ToolRegistry
ToolRegistryHolder.INSTANCE.allTools()   // → Collection<ToolMetadata>
```

### 2.1 Design Rationale

- **应用层不直接 new 聚合根** — `ToolRegistry` 由 `ToolRegistryHolder` 持有并管理生命周期
- **全局单例** — 工具注册是全局唯一的（一个 JVM 一个能力目录），无需多实例
- **快捷方法** — `allTools()` 直接代理到 `ToolRegistry.allTools()`，方便查询场景

### 2.2 Usage in Facades

```java
// CLI Facade: ExecutionCoordinator
ToolRegistry tr = ToolRegistryHolder.INSTANCE.registry();
var discovery = new ToolDiscovery(tr);
discovery.scanAndRegister(List.of(new BuiltinTools()));
agent.withToolRegistry(tr);

// TUI Facade: AliceTuiLauncher  — 通过 SPI 在 bootstrap 中注入
```

## 3. Aggregate Root: `ToolRegistry`

**6 个 public 方法**:

| Method | Purpose | Called By |
|--------|---------|-----------|
| `register(ToolMetadata)` | 注册工具 (幂等) | `ToolDiscovery` (领域服务) |
| `lookup(String)` | 按名查找 | `ExecutionEngine` (领域服务) |
| `hasTool(String)` | 检查存在性 | CLI `/tool list` / `Agent` |
| `toolNames()` | 获取所有工具名 | CLI `/tool list` / `Agent` |
| `allTools()` | 获取所有元数据 | CLI `/tool list` / `AgentExecutor` |
| `unregister(String)` | 移除工具 | 管理接口 |
| `size()` | 工具数量 | 管理接口 |

## 4. Domain Services

### 4.1 `ToolDiscovery` — 1 个 public 方法

| Method | Purpose |
|--------|---------|
| `scanAndRegister(List<Object>)` | 扫描 `@AgentTool` 注解 Bean, 注册到 `ToolRegistry` |

依赖: `ToolRegistry` (聚合根)

### 4.2 `ExecutionEngine` — 2 个 public 方法

| Method | Purpose |
|--------|---------|
| `invoke(String, Map)` | 执行工具 (含沙箱选择 + 超时控制) |
| `shutdown()` | 释放线程池 |

依赖: `ToolRegistry` (聚合根), `SandboxProvider` (策略)

## 5. Access Flow Summary

```
Facade (CLI/TUI)
  │
  │ 1. ToolRegistryHolder.INSTANCE.registry()
  ▼
ToolRegistry (聚合根)
  │
  ├── 2a. ToolDiscovery(registry).scanAndRegister(beans)
  │         ↓
  │      registry.register(metadata)     ← 领域服务写入聚合根
  │
  ├── 2b. ExecutionEngine.builder().registry(registry).build()
  │         ↓
  │      registry.lookup(name)           ← 领域服务读取聚合根
  │      metadata.invoke(params)         ← 实体行为
  │         ↓
  │      return ToolResult               ← 新值对象
  │
  └── 2c. registry.toolNames() / allTools()   ← 直接查询聚合根
```

## 6. Method Count Control

| Class | Public Methods | Role | Status |
|-------|---------------|------|--------|
| `ToolRegistry` | 6 | Aggregate Root | ✅ 合理 |
| `ToolDiscovery` | 1 | Domain Service | ✅ 极简 |
| `ExecutionEngine` | 2 | Domain Service | ✅ 极简 |
| `ToolRegistryHolder` | 2 | Inbound Entry | ✅ 极简 |

## 7. Related Documents

- [DESIGN.md](./DESIGN.md) — Module design overview
- [capability.md](./capability.md) — Tool capability definitions
- [e2e/scene-tool-gateway-endpoints.md](./e2e/scene-tool-gateway-endpoints.md) — Hole test scene
