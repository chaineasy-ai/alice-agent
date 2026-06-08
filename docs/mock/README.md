# Mock 测试 — LLM 模拟 (aimock)

基于 `@copilotkit/aimock` 为 Alice Agent 提供 LLM API 模拟，无需真实 API Key 即可运行 Agent 测试。

**Alice Agent 接入的模型供应商**:
- **OpenAI** — `OpenAiSupplier`, 注册名为 `"openai"`, 支持 `gpt-4o`, `gpt-4o-mini`, `o1` 等
- **Gemma4** — `Gemma4Supplier`, 注册名为 `"gemma4"`, 本地部署的 OpenAI 兼容服务 (`http://192.168.1.14:10303/v1/chat/completions`)

---

## 快速开始

### 1. 启动 Mock LLM 服务

```bash
npx @copilotkit/aimock@latest -c docs/mock/aimock.json
```

aimock 在 `http://localhost:4010` 监听，自动暴露 OpenAI Chat Completions 兼容接口 `POST /v1/chat/completions`。

### 2. 设置环境变量

将流量重定向到 aimock:

```powershell
# PowerShell
$env:OPENAI_API_KEY = "aimock-placeholder"
$env:OPENAI_BASE_URL = "http://localhost:4010/v1"
```

```bash
# Bash
export OPENAI_API_KEY="aimock-placeholder"
export OPENAI_BASE_URL="http://localhost:4010/v1"
```

### 3. 运行 Alice Agent

```bash
./gradlew :alice-bootstrap:run --args="-m gpt-4o --verbose"
```

所有 LLM 请求由 aimock 返回 fixture 中定义的确定性响应。

---

## Fixtures 配置

Fixtures 是 aimock 定义模拟响应的核心方式。每个 fixture 包含 `match`（匹配条件）和 `response`（响应内容）。

### 匹配字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `userMessage` | string/RegExp | 匹配最后一条用户消息内容 |
| `model` | string/RegExp | 匹配请求的模型名称 |
| `turnIndex` | number | 匹配第 N 轮对话（按 assistant 消息计数） |
| `hasToolResult` | boolean | 是否包含 tool 响应消息 |
| `toolCallId` | string | 匹配 tool_call_id |
| `toolName` | string | 匹配工具函数名称 |
| `sequenceIndex` | number | 匹配第 N 次出现的模式 |
| `responseFormat` | string | 匹配 response_format.type |

### 响应字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `content` | string | 文本响应内容 |
| `toolCalls` | array | 工具调用列表 `[{name, arguments}]` |
| `refusal` | string | 拒绝消息（可选） |
| `latency` | number | 模拟延迟(ms) |
| `chunkSize` | number | 流式分块大小(字符数) |

---

## Fixtures 文件

### `fixtures/chat.json` — 基础对话 + Tool Calling

```json
{
  "fixtures": [
    {
      "match": { "userMessage": "hello", "model": "gpt-4o" },
      "response": { "content": "Hello! I am Alice Agent, a modular Java agent framework. I can help with planning, tool integration, memory, and guardrails." }
    },
    {
      "match": { "userMessage": "hello", "model": "gpt-4o-mini" },
      "response": { "content": "Hi there! Alice Agent ready." }
    },
    {
      "match": { "userMessage": "weather", "model": "gpt-4o" },
      "response": {
        "toolCalls": [{ "name": "get_weather", "arguments": { "city": "Beijing" } }]
      }
    },
    {
      "match": { "userMessage": "search*", "model": "gpt-4o" },
      "response": {
        "toolCalls": [{ "name": "search/web", "arguments": { "query": "default" } }]
      }
    },
    {
      "match": { "userMessage": "*", "model": "gpt-4o", "hasToolResult": true },
      "response": {
        "content": "Based on the tool results, I can confirm the operation completed successfully."
      }
    }
  ]
}
```

### `fixtures/local-model.json` — 本地 Gemma4 模型模拟

```json
{
  "fixtures": [
    {
      "match": { "userMessage": "hello", "model": "gemma-4" },
      "response": { "content": "Hello from Gemma 4 (local mock)!" }
    },
    {
      "match": { "userMessage": "*", "model": "gemma-4" },
      "response": { "content": "This is a mock response from the local Gemma-4 model." }
    }
  ]
}
```

---

## 使用方式

### 方式 A: 使用基础配置启动

```bash
npx @copilotkit/aimock@latest -c docs/mock/aimock.json
```

### 方式 B: 自定义配置加载不同 fixtures

新建 `aimock.json` 并指定 fixture 目录:

```json
{
  "llm": {
    "fixtures": "./docs/mock/fixtures"
  }
}
```

```bash
npx @copilotkit/aimock@latest -c aimock.json
```

### 方式 C: 启用流式模拟参数

新建 `aimock-stream.json`:
```json
{
  "port": 4010,
  "llm": {
    "fixtures": "./docs/mock/fixtures/streaming.json",
    "metrics": true
  }
}
```

```bash
npx @copilotkit/aimock@latest -c aimock-stream.json
```

---

## Alice Agent 集成说明

### 环境变量

| 变量 | 值 | 作用 |
|------|-----|------|
| `OPENAI_BASE_URL` | `http://localhost:4010/v1` | 重定向 OpenAI 流量到 aimock |
| `OPENAI_API_KEY` | `aimock-placeholder` | 任意占位符，aimock 不校验 |

### 代码层面

在 `AliceAgent.java` 的 `initializeModelProvider()` 中:
- 读取 `OPENAI_API_KEY` 环境变量
- 创建 `OpenAiSupplier(apiKey)`，使用 `OPENAI_BASE_URL` 作为 baseUrl
- 通过 `OPENAI_BASE_URL` 环境变量覆盖 `OpenAiSupplier` 的默认 `https://api.openai.com/v1/chat/completions`

### 覆盖的模型

| 模型 ID | Supplier | 路由来源 |
|---------|----------|---------|
| `gpt-4o` | `openai` | `ModelEnum.GPT_4O` |
| `gpt-4o-mini` | `openai` | `ModelEnum.GPT_4O_MINI` |
| `o1` | `openai` | `ModelEnum.O1` |
| `o1-mini` | `openai` | `ModelEnum.O1_MINI` |
| `gemma-4` | `gemma4` | `ModelEnum.GEMMA_4` |

---

## 验证 Mock 是否生效

```bash
curl http://localhost:4010/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"gpt-4o","messages":[{"role":"user","content":"hello"}]}'
```

返回包含 `choices[0].message.content` 的 OpenAI 标准格式 JSON。

---

## 故障排查

| 现象 | 原因 | 解决 |
|------|------|------|
| 连接被拒绝 | aimock 未启动 | 运行 `npx @copilotkit/aimock@latest -c docs/mock/aimock.json` |
| 返回真实 API 响应 | `OPENAI_BASE_URL` 未正确设置 | 在创建 `OpenAiSupplier` 前设置环境变量 |
| 未匹配 fixture | `model` 或 `userMessage` 不匹配 | 添加 `*` 通配符 fixture 作为兜底 |
| 端口冲突 | 4010 被占用 | 使用 `--port 4011` 指定其他端口 |
