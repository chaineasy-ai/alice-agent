---
title: "alice-model 配置文件说明"
summary: "~/.alice/model.json 配置文件完整说明 — providers.<name>.available_models[] 结构，Jackson 解析"
read_when:
  - "配置模型提供商或修改默认模型"
  - "理解 enable_thinking / reasoning_effort 参数"
  - "排查模型加载问题"
---

## model.json 配置说明文档

### 1. 文件位置

```
~/.alice/model.json
```

| 操作系统 | 完整路径 |
|----------|----------|
| Linux / macOS | `/home/[username]/.alice/model.json` |
| Windows | `C:\Users\[username]\.alice\model.json` |

### 2. 创建配置文件

```bash
mkdir -p ~/.alice
touch ~/.alice/model.json
```

### 3. 配置结构

```
~/.alice/model.json
├── default_model     { provider, model, enable_thinking, reasoning_effort }
├── planner           { instruction_model_id, reasoning_model_id,
│                       instruction{ enable_thinking, reasoning_effort },
│                       reasoning{ enable_thinking, reasoning_effort } }
└── providers         { <name>:
    ├── base_url
    ├── api_key        (支持 ${ENV_VAR})
    └── available_models[]
        └── { name, model, max_tokens, max_output_tokens, capabilities }
```

---

### 4. 完整配置模板

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
    },
    "openai": {
      "base_url": "https://api.openai.com/v1",
      "api_key": "${OPENAI_API_KEY}",
      "available_models": [
        {
          "name": "o3-mini",
          "model": "o3-mini",
          "max_tokens": 128000,
          "max_output_tokens": 16000,
          "capabilities": {
            "tools": true,
            "images": false,
            "parallel_tool_calls": false,
            "prompt_cache_key": false,
            "chat_completions": true
          }
        }
      ]
    }
  }
}
```

### 5. 字段说明

#### 5.1 default_model

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `provider` | string | ✅ | 提供商名称，对应 `providers` 中的键 |
| `model` | string | ✅ | 模型标识符，对应 `available_models[].name` |
| `enable_thinking` | boolean | ❌ | 全局思考开关。默认 `true` |
| `reasoning_effort` | string | ❌ | `low` / `medium` / `high` / `xhigh`。默认 `high`（thinking=true）或 `low`（thinking=false） |

#### 5.2 planner

| 字段 | 类型 | 说明 |
|------|------|------|
| `instruction_model_id` | string | 快路径模型 ID，未设置回退到 `default_model.model` |
| `reasoning_model_id` | string | 慢路径模型 ID，未设置回退到 `default_model.model` |
| `instruction` | object | 快路径 thinking 参数 `{ enable_thinking, reasoning_effort }` |
| `reasoning` | object | 慢路径 thinking 参数 `{ enable_thinking, reasoning_effort }` |

#### 5.3 providers.<name>

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `base_url` | string | ✅ | API 基础 URL，须以 `http://` 或 `https://` 开头 |
| `api_key` | string | ❌ | API 密钥，支持 `${ENV_VAR}` 环境变量引用 |
| `available_models` | array | ✅ | 可用模型列表 |

#### 5.4 available_models[]

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `name` | string | ✅ | 模型名称（调用标识符） |
| `model` | string | ❌ | 发送给 API 的模型名，默认同 `name` |
| `max_tokens` | integer | ✅ | 最大上下文总长度 |
| `max_output_tokens` | integer | ❌ | 单次最大输出 token 数，默认同 `max_tokens` |
| `capabilities` | object | ✅ | 模型能力标识集 |

#### 5.5 capabilities

| 字段 | 类型 | 说明 |
|------|------|------|
| `tools` | boolean | 是否支持工具/函数调用 |
| `images` | boolean | 是否支持图像输入（多模态） |
| `parallel_tool_calls` | boolean | 是否支持并行工具调用 |
| `prompt_cache_key` | boolean | 是否支持提示缓存键 |
| `chat_completions` | boolean | 是否支持 `/chat/completions` 端点 |

---

### 6. 映射规则

`enable_thinking` / `reasoning_effort` 在 `OpenAiSupplier` 中按供应商映射：

| 供应商 | enable_thinking | API 行为 |
|--------|----------------|----------|
| DeepSeek | `false` | `thinking:{"type":"disabled"}` |
| DeepSeek | `true` + `effort` | `thinking:{"type":"enabled", "effort":"..."}` |
| OpenAI o 系列 | `false` | 强制 `reasoning_effort="low"` |
| OpenAI o 系列 | `true` + `effort` | 透传 `reasoning_effort` |

### 7. 双路径模型

`DefaultPlannerModelSupplier` 根据路径自动注入 thinking 参数：

| 路径 | enable_thinking | reasoning_effort | 用途 |
|------|----------------|------------------|------|
| **FastPath** (指令模型) | `false` | `low` | 快速响应，关闭 LLM 思考 |
| **SlowPath** (推理模型) | `true` | `high` | 深度推理、MCTS 规划 |

### 8. 环境变量配置

```bash
export DEEPSEEK_API_KEY=sk-xxxxxxxxxxxxxxxxxxxxxxxx
export OPENAI_API_KEY=sk-xxxxxxxxxxxxxxxxxxxxxxxx
export ANTHROPIC_API_KEY=sk-ant-xxxxxxxxxxxxxxxxxxxxx
```

### 9. 校验规则

| 校验项 | 规则 | 错误示例 |
|--------|------|----------|
| `max_tokens` | ≥ `max_output_tokens` | `max_tokens: 1000, max_output_tokens: 2000` ❌ |
| `base_url` | 必须以 `http://` 或 `https://` 开头 | `api.example.com/v1` ❌ |
| `name` | 非空字符串 | `""` ❌ |
| `available_models` | 至少 1 个模型 | `[]` ❌ |

### 10. 权限建议

```bash
chmod 700 ~/.alice
chmod 600 ~/.alice/model.json
```

### 11. 常见问题

| 问题 | 解决方案 |
|------|----------|
| 配置文件找不到 | 确认 `~/.alice/model.json` 是否存在 |
| API Key 未生效 | 检查环境变量名称是否与 `${VAR_NAME}` 一致 |
| 权限错误 | 执行 `chmod 600 ~/.alice/model.json` |
| JSON 格式错误 | 使用 `jq . ~/.alice/model.json` 验证 |
