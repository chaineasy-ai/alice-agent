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

模型提供商与模型定义文件，由 `alice-model` 模块的 `ModelConfigLoader` 读取。

### 完整配置模板

```json
{
  "default_model": "deepseek-chat",
  "language_models": {
    "openai_compatible": {
      "deepseek": {
        "base_url": "https://api.deepseek.com/v1",
        "api_key": "${DEEPSEEK_API_KEY}",
        "available_models": [
          {
            "name": "deepseek-chat",
            "max_tokens": 200000,
            "max_output_tokens": 32000,
            "max_completion_tokens": 200000,
            "capabilities": {
              "tools": true,
              "images": false,
              "parallel_tool_calls": true,
              "prompt_cache_key": true,
              "chat_completions": true
            }
          }
        ]
      },
      "openai": {
        "base_url": "https://api.openai.com/v1",
        "api_key": "${OPENAI_API_KEY}",
        "available_models": [
          {
            "name": "gpt-4o-mini",
            "max_tokens": 128000,
            "max_output_tokens": 16384,
            "max_completion_tokens": 128000,
            "capabilities": {
              "tools": true,
              "images": true,
              "parallel_tool_calls": true,
              "prompt_cache_key": false,
              "chat_completions": true
            }
          }
        ]
      }
    }
  }
}
```

### 环境变量展开

`api_key` 字段支持 `${ENV_VAR_NAME}` 语法。`ModelConfigLoader.expandEnvVar()` 在加载时自动读取 `System.getenv(envVar)` 替换：

```bash
# ~/.bashrc 或 ~/.zshrc
export DEEPSEEK_API_KEY=sk-xxxxxxxxxxxxxxxxxxxxxxxx
export OPENAI_API_KEY=sk-xxxxxxxxxxxxxxxxxxxxxxxx
```

### 字段说明

| 层级 | 字段 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|------|--------|------|
| 根 | `default_model` | string | ❌ | — | 默认模型 ID（推理/慢路径 System 2 使用） |
| 根 | `instruction_model` | string | ❌ | 同 `default_model` | 指令模型 ID（快路径 System 1 使用），用于快速简单任务；未设置时回退到 `default_model` |
| 根 | `language_models` | object | ✅ | — | 语言模型配置根节点 |
| 2 | `openai_compatible` | object | ✅ | — | OpenAI 兼容接口标识 |
| 3 | `[provider_name]` | object | ✅ | — | 提供商自定义名称 |
| 4 | `base_url` | string | ✅ | — | API 基础 URL（须以 `http://` 或 `https://` 开头） |
| 4 | `api_key` | string | ❌ | `""` | API 密钥，支持 `${ENV_VAR}` 环境变量引用 |
| 4 | `available_models` | array | ✅ | — | 支持的模型列表（至少 1 个） |
| 5 | `name` | string | ✅ | — | 模型名称（调用时使用的标识符） |
| 5 | `max_tokens` | int | ✅ | — | 最大上下文总长度（输入 + 输出 token） |
| 5 | `max_output_tokens` | int | ✅ | — | 单次最大输出 token 数 |
| 5 | `max_completion_tokens` | int | ✅ | 同 `max_tokens` | 补全接口最大 token 数 |
| 6 | `capabilities.tools` | bool | ✅ | — | 是否支持工具/函数调用 |
| 6 | `capabilities.images` | bool | ✅ | — | 是否支持图像输入（多模态） |
| 6 | `capabilities.parallel_tool_calls` | bool | ✅ | — | 是否支持并行工具调用 |
| 6 | `capabilities.prompt_cache_key` | bool | ✅ | — | 是否支持提示缓存 |
| 6 | `capabilities.chat_completions` | bool | ✅ | — | 是否支持 `/chat/completions` 端点 |

### 提供商路由

Provider 名称决定使用的 `ModelSupplier` 实现：

| Provider 名称 | Supplier 类 | 说明 |
|---------------|-------------|------|
| `openai` | `OpenAiSupplier` | OpenAI Chat Completion API |
| `gemma4`, `gemma` | `Gemma4Supplier` | 本地 Gemma 4 推理 |
| 其他 (默认) | `OpenAiSupplier` | 所有 OpenAI 兼容 API（如 deepseek） |

### 双路径模型选择

PlannerService 根据任务复杂度自动选择路径：

| 路径 | 系统 | 使用的模型配置 | 适用场景 |
|------|------|----------------|----------|
| **FastPath** (System 1) | 快速指令 | `instruction_model`（默认回退 `default_model`） | 简单查询、问候、短任务 |
| **SlowPath** (System 2) | 深度推理 | `default_model` | 复杂分析、多步骤规划、MCTS 树搜索 |

配置示例：

```json
{
  "default_model": "deepseek-v4-flash",
  "instruction_model": "gpt-4o-mini",
  ...
}
```

若仅设置 `default_model` 而未指定 `instruction_model`，则 FastPath 与 SlowPath 使用同一模型。

### 校验规则

| 校验项 | 规则 | 错误示例 |
|--------|------|----------|
| `max_tokens` | ≥ `max_output_tokens` | `max_tokens: 1000, max_output_tokens: 2000` ❌ |
| `max_completion_tokens` | 建议等于 `max_tokens` | 相差较大时可能产生歧义 |
| `base_url` | 必须以 `http://` 或 `https://` 开头 | `api.example.com/v1` ❌ |
| `name` | 非空字符串 | `""` ❌ |
| `available_models` | 至少 1 个模型 | `[]` ❌ |
| `capabilities` | 所有布尔字段必须显式设置 | 缺失字段可能导致运行时错误 |

---

## 示例文件

| 文件 | 说明 |
|------|------|
| [config.json](./config.json) | 系统基础设置示例（扁平键） |
| [model.json](./model.json) | Model 配置示例（旧版扁平结构 — 仅供参考） |
| [example.yaml](./example.yaml) | 配置键参考（YAML，文档用） |

> **注意**：目录中的 [model.json](./model.json) 是旧版 `AliceConfigStore` 用的扁平结构，仅供参考。
> 实际应使用 `~/.alice/model.json` 的 `language_models.openai_compatible` 结构（见上方）。

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
