---
title: "Alice Agent Configuration"
summary: "Configuration system for Alice Agent — two config files at ~/.alice/: config.json for system settings, model.json for model/provider definitions with environment variable expansion"
read_when:
  - "implementing or debugging config management"
  - "working on CLI / TUI frontend config"
  - "understanding model/provider registration and priority order"
  - "adding new models, providers, or env var references"
  - "copying or setting up model.json for alice-model"
scope:
  - alice-facade-cmd
  - alice-model
status: "active"
updated: "2026-06-29"
---
# Alice Agent Configuration

Alice Agent uses **two configuration files** in `~/.alice/`:

| File | Loader | Purpose |
|------|--------|---------|
| `~/.alice/config.json` | `AliceConfigStore` | System settings (timeout, verbose, max_iterations, etc.) |
| `~/.alice/model.json` | `ModelConfigLoader` | Model provider definitions, API URLs, API keys, model lists |

---

## 1. System Config (`~/.alice/config.json`)

### Config Structure

配置文件使用 JSON 格式，均为根级扁平键，使用下划线命名：

```json
{
  "default_timeout": 180,
  "default_verbose": false,
  "max_iterations": 10,
  "action_timeout_ms": 30000,
  "max_micro_depth": 30
}
```

#### 键名路由规则

CLI 点分隔键名自动转换为 JSON 下划线键名：

| 键名格式 | 段数 | 存储方式 | 示例 |
|----------|------|----------|------|
| `{namespace}.{field}` (已知 namespace) | 2 | 扁平下划线 | `default.timeout` → `default_timeout` |
| `{key}` (单段) | 1 | 扁平 | `max_iterations` → `max_iterations` |

已知的扁平命名空间: `default`, `agent`, `action`

> **注意**：`providers.{name}.{field}` 嵌套结构和 `openai.*` / `anthropic.*` 扁平前缀是旧版遗留格式，
> 仍然可在 `AliceConfigStore` 中 get/set，但不再被模型初始化流程使用。
> 所有提供商配置（API Key、Base URL、模型列表）已迁移至 `~/.alice/model.json`
> 的 `language_models.openai_compatible` 结构下（详见[第 2 节](#2-model-config-alicemodeljson)）。

### 配置键参考

#### 系统基础设置

| CLI 键 | JSON 键 | 类型 | 默认值 | 说明 |
|--------|---------|------|--------|------|
| `default.timeout` | `default_timeout` | int | `180` | 默认任务超时（秒） |
| `default.verbose` | `default_verbose` | bool | `false` | 默认详细模式 |
| `max_iterations` | `max_iterations` | int | `10` | 最大 PPAO 迭代次数 |
| `max_micro_depth` | `max_micro_depth` | int | `30` | Micro-ReAct 最大递归深度（熔断阈值），高于 Macro 迭代以支持多步骤工具链 |
| `action_timeout_ms` | `action_timeout_ms` | int | `30000` | Action 执行超时（毫秒） |

#### 模型选择（可选覆盖）

以下键为 `config.json` 中可选的模型覆盖配置。主要模型配置位于 `~/.alice/model.json`（详见[第 2 节](#2-model-config-alicemodeljson)）。

| CLI 键 | JSON 键 | 默认值 | 说明 |
|--------|---------|------|--------|
| `default.model` | `default_model` | `gpt-4o-mini` | 默认模型 ID（可选覆盖；主配置见 model.json 的 `default_model`） |
| `agent.max_iterations` | `max_iterations` | `10` | 最大迭代次数（同 `max_iterations`，可选覆盖） |
| `agent.max_micro_depth` | `max_micro_depth` | `30` | Micro-ReAct 最大递归深度（同 `max_micro_depth`，可选覆盖） |

> **注意**：`openai.*` / `anthropic.*` 等提供商配置键已从 `config.json` 迁移至 `model.json`。
> 在 `AliceConfigStore` 中仍然可以 get/set 这些旧键，但模型初始化流程不再读取它们。

### 读取优先级

1. **环境变量** — 最高优先级，来源标注 `(from env <VAR>)`
2. **配置文件** (`~/.alice/config.json`) — 来源标注 `(from ~/.alice/config.json)`
3. **内建默认值** — 最低优先级，来源标注 `(built-in default)`

环境变量映射（作为 model.json 未配置时的 fallback）：

| 环境变量 | 用途 |
|----------|------|
| `OPENAI_API_KEY` | OpenAI API 密钥（fallback） |
| `ANTHROPIC_API_KEY` | Anthropic API 密钥（fallback） |
| `DEEPSEEK_API_KEY` | DeepSeek API 密钥（fallback） |

> 推荐方式：在 `~/.alice/model.json` 中配置 `${ENV_VAR}` 引用，
> 或在环境变量中直接设置。环境变量优先级高于 `model.json` 中的字面值。

### CLI 用法

```bash
# 显示全部配置
alice config

# 获取单个值（环境变量优先，然后配置文件，最后默认值）
alice config get default.timeout
alice config get max_iterations

# 设置系统配置值（持久化到 ~/.alice/config.json）
alice config set default.timeout 300
alice config set max_iterations 25

# 删除值
alice config set default.timeout ""   # 设为空
# 或直接编辑 ~/.alice/config.json

# 查看帮助
alice config --help
```

> 提供商 API Key 等敏感配置推荐在 `~/.alice/model.json` 中以 `${ENV_VAR}` 方式引用，
> 或通过环境变量直接设置，而非明文写入 `config.json`。

---

## 2. Model Config (`~/.alice/model.json`)

模型提供商与模型定义文件，由 `alice-model` 模块的 `ModelConfigLoader`（Jackson 解析）读取。

### 完整配置模板

```json
{
  "default_model": {
    "provider": "deepseek",
    "model": "deepseek-v4-flash",
    "enable_thinking": true,
    "reasoning_effort": "high"
  },
  "planner": {
    "instruction_model_id": "deepseek-v4-flash",
    "reasoning_model_id": "deepseek-v4-flash",
    "instruction": {
      "enable_thinking": false,
      "reasoning_effort": "low"
    },
    "reasoning": {
      "enable_thinking": true,
      "reasoning_effort": "high"
    }
  },
  "providers": {
    "deepseek": {
      "base_url": "https://api.deepseek.com/v1",
      "api_key": "${DEEPSEEK_API_KEY}",
      "available_models": [
        {
          "name": "deepseek-v4-flash",
          "model": "deepseek-v4-flash",
          "max_tokens": 131072,
          "max_output_tokens": 32000,
          "capabilities": {
            "tools": true,
            "images": false,
            "parallel_tool_calls": true,
            "prompt_cache_key": true,
            "chat_completions": true
          }
        }
      ]
    }
  }
}
```

### 环境变量展开

`api_key` 字段支持 `${ENV_VAR_NAME}` 语法。`ModelConfigLoader.expandEnvVar()` 在加载时自动读取 `System.getenv(envVar)` 替换：

```bash
export DEEPSEEK_API_KEY=sk-xxxxxxxxxxxxxxxxxxxxxxxx
export OPENAI_API_KEY=sk-xxxxxxxxxxxxxxxxxxxxxxxx
```

### 字段说明

#### default_model

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `provider` | string | ✅ | 提供商名称，对应 `providers` 中的键 |
| `model` | string | ✅ | 模型标识符，对应 `available_models[].name` |
| `enable_thinking` | bool | ❌ | 全局思考开关。默认 `true` |
| `reasoning_effort` | string | ❌ | 推理档位 `low/medium/high/xhigh`。默认 `high`（thinking=true）或 `low`（thinking=false） |

#### planner

| 字段 | 类型 | 说明 |
|------|------|------|
| `instruction_model_id` | string | 快路径模型 ID（FastPath），未设置回退到 `default_model` |
| `reasoning_model_id` | string | 慢路径模型 ID（SlowPath），未设置回退到 `default_model` |
| `instruction` | object | 快路径 thinking 参数 `{ enable_thinking, reasoning_effort }` |
| `reasoning` | object | 慢路径 thinking 参数 `{ enable_thinking, reasoning_effort }` |

#### providers.<name>

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `base_url` | string | ✅ | API 基础 URL（须以 `http://` 或 `https://` 开头） |
| `api_key` | string | ❌ | API 密钥，支持 `${ENV_VAR}` 环境变量引用 |
| `available_models` | array | ✅ | 可用模型列表 |

#### available_models[]

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `name` | string | ✅ | 模型名称（调用标识符） |
| `model` | string | ❌ | API 模型名，默认同 `name` |
| `max_tokens` | int | ✅ | 最大上下文总长度 |
| `max_output_tokens` | int | ❌ | 单次最大输出，默认同 `max_tokens` |
| `capabilities` | object | ✅ | 模型能力标识集 |

#### capabilities

| 字段 | 类型 | 说明 |
|------|------|------|
| `tools` | bool | 是否支持工具/函数调用 |
| `images` | bool | 是否支持图像输入 |
| `parallel_tool_calls` | bool | 是否支持并行工具调用 |
| `prompt_cache_key` | bool | 是否支持提示缓存 |
| `chat_completions` | bool | 是否支持 `/chat/completions` 端点 |

### 映射规则

`enable_thinking` / `reasoning_effort` 在 `OpenAiSupplier` 中按供应商映射：

| 供应商 | enable_thinking | API 行为 |
|--------|----------------|----------|
| DeepSeek | `false` | `thinking:{"type":"disabled"}` |
| DeepSeek | `true` + `effort` | `thinking:{"type":"enabled", "effort":"..."}` |
| OpenAI o 系列 | `false` | 强制 `reasoning_effort="low"` |
| OpenAI o 系列 | `true` + `effort` | 透传 `reasoning_effort` |

### 提供商路由

Provider 名称决定使用的 `ModelSupplier` 实现：

| Provider 名称 | Supplier 类 | 说明 |
|---------------|-------------|------|
| `openai` | `OpenAiSupplier` | OpenAI Chat Completion API |
| `gemma4`, `gemma` | `Gemma4Supplier` | 本地 Gemma 4 推理 |
| 其他 (默认) | `OpenAiSupplier` | 所有 OpenAI 兼容 API（如 deepseek） |

### 双路径模型选择

`DefaultPlannerModelSupplier` 根据路径自动注入不同参数：

| 路径 | enable_thinking | reasoning_effort | 用途 |
|------|----------------|------------------|------|
| **FastPath** (指令模型) | `false` | `low` | 简单查询、快速响应，关闭 LLM 思考 |
| **SlowPath** (推理模型) | `true` | `high` | 复杂分析、多步骤规划、MCTS 树搜索 |

### 校验规则

| 校验项 | 规则 | 错误示例 |
|--------|------|----------|
| `max_tokens` | ≥ `max_output_tokens` | `max_tokens: 1000, max_output_tokens: 2000` ❌ |
| `base_url` | 必须以 `http://` 或 `https://` 开头 | `api.example.com/v1` ❌ |
| `name` | 非空字符串 | `""` ❌ |
| `available_models` | 至少 1 个模型 | `[]` ❌ |

---

## 示例文件

| 文件 | 说明 |
|------|------|
| [config.json](./config.json) | 系统基础设置示例（扁平键） |
| [model.json](./model.json) | Model 配置示例（新格式） |
| [example.yaml](./example.yaml) | 配置键参考（YAML，文档用） |

## 设计细节

- **`AliceConfigStore`** — 底层 JSON 读写，支持路径解析（用于 `config.json`）
- **`ModelConfigLoader`** — 模型配置加载，环境变量展开（用于 `model.json`）
- **线程安全** — `AliceConfigStore` 通过 `synchronized` 方法保证
- **无外部依赖** — 仅使用 `java.nio.file` + `com.fasterxml.jackson.databind`
- **优雅降级** — 文件不存在或损坏时返回空配置
- **原子写入** — 先写 `.tmp` 文件，再 `ATOMIC_MOVE` 替换原文件

### 权限建议

```bash
chmod 700 ~/.alice
chmod 600 ~/.alice/config.json
chmod 600 ~/.alice/model.json
```
