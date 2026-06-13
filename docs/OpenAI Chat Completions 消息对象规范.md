---
title: "OpenAI Chat Completions 消息对象规范"
summary: "OpenAI Chat Completions message object specification"
read_when:
  - "implementing or debugging OpenAI model adapter"
scope:
  - "alice-model"
status: "active"
updated: "2026-06-13"
---
# OpenAI Chat Completions 消息对象规范（Agent 通用版）
上下文整体结构：`messages: Array[Message]`，按角色划分不同消息结构，**同时兼容业务通信 + LLM 微调**。

## 一、角色分类 & 字段规范
### 1. 系统/开发者消息（system / developer）
**作用**：设定AI身份、行为规则、约束条件；开源/微调场景优先使用 `system`。
```json
{
  "role": "system",
  "content": "你是一个有用的AI助手。",
  "name": "example_bot"
}
```
- 必填：`role`、`content`
- 选填：`name`（角色标识，仅支持字母/数字/下划线）

### 2. 用户消息（user）
#### 2.1 纯文本模式
```json
{
  "role": "user",
  "content": "北京今天天气怎么样？",
  "name": "Alice"
}
```
- 必填：`role`、`content`
- 选填：`name`（多用户场景区分身份）

#### 2.2 多模态模式（文本+图片，适配视觉模型）
`content` 由字符串改为对象数组
```json
{
  "role": "user",
  "content": [
    { "type": "text", "text": "图里画了什么？" },
    { "type": "image_url", "image_url": { "url": "https://example.com/image.jpg" } }
  ]
}
```

### 3. 助理消息（assistant）
#### 3.1 纯文本回复
```json
{
  "role": "assistant",
  "content": "今天北京天气晴朗，18度。",
  "name": "ai_assistant"
}
```
- 必填：`role`、`content`
- 选填：`name`

#### 3.2 工具调用模式（Agent 核心）
触发函数调用时 `content` 设为 `null`，通过 `tool_calls` 下发调用指令
```json
{
  "role": "assistant",
  "content": null,
  "tool_calls": [
    {
      "id": "call_abc123",
      "type": "function",
      "function": {
        "name": "get_weather",
        "arguments": "{\"location\": \"Beijing\"}"
      }
    }
  ]
}
```
- 核心说明：
  - `id`：调用唯一标识，用于消息链路配对
  - `type`：固定值 `function`
  - `arguments`：参数必须为 **JSON 字符串**

### 4. 工具响应消息（tool）
**作用**：回传工具执行结果，衔接工具与大模型，`tool_call_id` 必须和上游调用ID一一对应。
```json
{
  "role": "tool",
  "tool_call_id": "call_abc123",
  "name": "get_weather",
  "content": "{\"temperature\": \"18°C\", \"condition\": \"Sunny\"}"
}
```
- 全部字段均为必填项
- `content` 建议使用 JSON 字符串/纯文本格式

## 二、完整微调数据集示例（JSONL 标准）
行业主流采用 **JSONL 格式**（单行一条样本），可直接用于 LLaMA-Factory、Unsloth、OpenAI 微调平台。
```json
{
  "messages": [
    { "role": "system", "content": "你是一个天气助手。" },
    { "role": "user", "content": "帮我看看北京的天气。" },
    {
      "role": "assistant",
      "content": null,
      "tool_calls": [
        {
          "id": "call_001",
          "type": "function",
          "function": {
            "name": "get_weather",
            "arguments": "{\"location\":\"Beijing\"}"
          }
        }
      ]
    },
    {
      "role": "tool",
      "tool_call_id": "call_001",
      "name": "get_weather",
      "content": "{\"temp\": \"18度\"}"
    },
    { "role": "assistant", "content": "北京现在18度，天气很好。" }
  ]
}
```

## 三、Message 实体字段定义（类型约束）
适配 Pydantic / TypeScript 等实体建模，统一数据结构：
1. `role`：枚举类型 → `["system", "user", "assistant", "tool"]`
2. `content`：`string | null | Array`
3. `name`：`string | null`
4. `tool_calls`：`Array | null`
5. `tool_call_id`：`string | null`

## 四、设计要点
1. 全链路遵循该规范，Agent 通信日志**无需转换**即可作为微调数据集；
2. 工具调用链路依靠 `id` 做配对校验，保证执行链路完整性；
3. 兼容纯对话、多模态、函数调用三类主流场景，通用性拉满。
