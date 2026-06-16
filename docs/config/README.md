---
title: "Alice Agent Configuration"
summary: "Configuration system for Alice Agent — JSON-based persistent config at ~/.alice/config.json with flat and nested key support"
read_when:
  - "implementing or debugging config management"
  - "working on CLI alice config subcommand"
  - "understanding config persistence and priority order"
  - "adding new config keys or providers"
scope:
  - alice-facade-cmd
status: "active"
updated: "2026-06-17"
---
# Alice Agent Configuration

## File Location

Config is stored at `~/.alice/config.json`. The directory and file are created automatically on first `alice config set`.

## Config Structure

配置文件使用 JSON 格式，支持两种键存储模式：

### 1. 嵌套结构 (Provider 配置)

`providers.{name}.{field}` 格式的键存储为嵌套 JSON 对象：

```json
{
  "default_model": "gpt-4o-mini",
  "providers": {
    "openai": {
      "api_key": "sk-...",
      "model": "gpt-4o-mini",
      "base_url": "https://api.openai.com/v1"
    },
    "anthropic": {
      "api_key": "sk-ant-...",
      "model": "claude-3.5-sonnet",
      "base_url": "https://api.anthropic.com/v1"
    }
  }
}
```

### 2. 扁平结构 (系统配置)

已知的 2 段命名空间键（`default.*`、`openai.*`、`anthropic.*`、`agent.*`、`action.*`）和 1 段键存储为扁平 `键_名` 格式：

```json
{
  "default_timeout": 180,
  "default_verbose": false,
  "max_iterations": 10,
  "action_timeout_ms": 30000
}
```

### 键名路由规则

| 键名格式 | 段数 | 存储方式 | 示例 |
|----------|------|----------|------|
| `providers.{name}.{field}` | 3+ | 嵌套对象 | `providers.openai.api_key` → `{providers: {openai: {api_key: "..."}}}` |
| `{namespace}.{field}` (已知 namespace) | 2 | 扁平下划线 | `default.timeout` → `default_timeout` |
| `{provider}.{field}` (已知 provider) | 2 | 扁平下划线 | `openai.api_key` → `openai_api_key` |
| `{key}` (单段) | 1 | 扁平 | `max_iterations` → `max_iterations` |

已知的扁平命名空间: `default`, `openai`, `anthropic`, `agent`, `action`

## 配置键参考

### 系统基础设置

| CLI 键 | JSON 键 | 类型 | 默认值 | 说明 |
|--------|---------|------|--------|------|
| `default.timeout` | `default_timeout` | int | `180` | 默认任务超时（秒） |
| `default.verbose` | `default_verbose` | bool | `false` | 默认详细模式 |
| `max_iterations` | `max_iterations` | int | `10` | 最大 PPAO 迭代次数 |
| `action_timeout_ms` | `action_timeout_ms` | int | `30000` | Action 执行超时（毫秒） |

### Provider 配置

| CLI 键 | JSON 路径 | 说明 |
|--------|-----------|------|
| `providers.openai.api_key` | `providers.openai.api_key` | OpenAI API 密钥 |
| `providers.openai.model` | `providers.openai.model` | OpenAI 使用的模型 |
| `providers.openai.base_url` | `providers.openai.base_url` | OpenAI API 端点 |
| `providers.anthropic.api_key` | `providers.anthropic.api_key` | Anthropic API 密钥 |
| `providers.anthropic.model` | `providers.anthropic.model` | Anthropic 使用的模型 |
| `providers.anthropic.base_url` | `providers.anthropic.base_url` | Anthropic API 端点 |

### 模型选择

| CLI 键 | JSON 键 | 默认值 | 说明 |
|--------|---------|--------|------|
| `default.model` | `default_model` | `gpt-4o-mini` | 默认使用的模型 ID |
| `agent.max_iterations` | `max_iterations` | `10` | 最大迭代次数（同 `max_iterations`） |

## 读取优先级

1. **环境变量** — 最高优先级，来源标注 `(from env <VAR>)`
2. **配置文件** (`~/.alice/config.json`) — 来源标注 `(from ~/.alice/config.json)`
3. **内建默认值** — 最低优先级，来源标注 `(built-in default)`

环境变量映射：

| 配置键 | 环境变量 |
|--------|----------|
| `openai.api_key` / `providers.openai.api_key` | `OPENAI_API_KEY` |
| `anthropic.api_key` / `providers.anthropic.api_key` | `ANTHROPIC_API_KEY` |

## CLI 用法

```bash
# 显示全部配置
alice config

# 获取单个值（环境变量优先，然后配置文件，最后默认值）
alice config get providers.openai.api_key
alice config get default.timeout

# 设置值（持久化到 ~/.alice/config.json）
alice config set providers.openai.api_key sk-xxx
alice config set default.timeout 300

# 删除值
alice config set providers.openai.api_key ""   # 设为空
# 或直接编辑 ~/.alice/config.json

# 查看帮助
alice config --help
```

## 示例文件

| 文件 | 说明 |
|------|------|
| [config.json](./config.json) | 系统基础设置示例（扁平键） |
| [model.json](./model.json) | Provider 配置示例（嵌套结构） |
| [example.yaml](./example.yaml) | 配置键参考（YAML，文档用） |

## 设计细节

- **`AliceConfigStore`** — 底层 JSON 读写，支持路径解析
- **线程安全** — 通过 `synchronized` 方法保证
- **无外部依赖** — 仅使用 `java.nio.file` + `com.fasterxml.jackson.databind`
- **优雅降级** — 文件不存在或损坏时返回空配置
- **原子写入** — 先写 `.tmp` 文件，再 `ATOMIC_MOVE` 替换原文件
