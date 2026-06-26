# LLM Function Calling（Tool Call）接口技术规范文档
## 文档版本
V1.0 | 适配标准：OpenAI Chat Completions 兼容协议（豆包/通义/DeepSeek/文心千帆通用）
## 适用范围
1. LLM 客户端、Agent 调度网关、工具执行引擎开发对接
2. 区分「请求侧工具注册」「响应侧工具调用指令」「终止状态字段」全链路定义
3. 包含流式/非流式完整示例、关键字段、分支判断逻辑、兼容方案

# 一、整体概念总览
## 1.1 两大核心阶段
1. **工具注册（Request 入参 tools）**
    调用方在请求时传入可用工具列表，告知模型可调用函数，仅存在于请求体。
2. **工具调用响应（Response message.tool_calls）**
    模型判断需要执行工具时，返回独立结构化字段 `tool_calls`，与文本内容 `content` 同级，**不内嵌在文本字符串中**。
## 1.2 终止标识字段 finish_reason
位于 `choices[*].finish_reason`，用于判定本轮生成终止类型，是区分「工具调用/正常回答/截断/风控」的核心判断依据。

# 二、请求结构：工具注册（tools 关键字段）
## 2.1 请求关键字段定义
| 字段 | 层级 | 类型 | 必填 | 说明 |
|------|------|------|------|------|
| tools | 根层级 | Array<Object> | 否 | 工具注册列表，开启函数调用能力的核心参数；不传则模型不会返回 tool_calls |
| tools[].type | tools 子项 | String | 是 | 固定值：`function`，仅支持函数类型工具 |
| tools[].function | tools 子项 | Object | 是 | 函数元数据定义 |
| function.name | function 内 | String | 是 | 工具唯一标识名，模型调用时原样返回 |
| function.description | function 内 | String | 是 | 工具功能描述，供模型判断何时调用 |
| function.parameters | function 内 | Object | 是 | JSON Schema，定义入参结构、类型、必填项 |

## 2.2 完整请求示例（非流式）
```http
POST /v1/chat/completions
Content-Type: application/json
Authorization: Bearer {API_KEY}
```
```json
{
  "model": "doubao-pro",
  "messages": [
    {"role": "user", "content": "上海今天气温多少？"}
  ],
  "tools": [
    {
      "type": "function",
      "function": {
        "name": "get_weather",
        "description": "查询指定城市当日天气、温度",
        "parameters": {
          "type": "object",
          "required": ["city"],
          "properties": {
            "city": {
              "type": "string",
              "description": "城市中文名"
            }
          }
        }
      }
    }
  ],
  "stream": false,
  "max_tokens": 1024
}
```

# 三、响应结构：Tool Call 结构化返回（核心）
## 3.1 响应顶层关键字段
| 字段 | 层级 | 说明 |
|------|------|------|
| choices | 根层级 | 生成结果数组，单轮对话仅返回 1 条 |
| choices[].finish_reason | choices 子项 | 生成终止状态枚举，核心分支判断字段 |
| choices[].message | choices 子项 | 模型输出消息体，承载文本与工具调用 |
| message.content | message 内 | 文本回答；触发工具调用时通常为 null |
| message.tool_calls | message 内 | 工具调用数组，**独立字段，不嵌入 content** |

## 3.2 tool_calls 子字段定义
| 字段 | 层级 | 说明 |
|------|------|------|
| id | tool_calls 子项 | 工具调用唯一 ID，多轮对话回填 tool 消息时必须携带 |
| type | tool_calls 子项 | 固定 `function` |
| function.name | tool_calls[].function | 要执行的工具名称，与请求 tools.name 一一对应 |
| function.arguments | tool_calls[].function | JSON 字符串，工具入参，需 JSON.parse 解析 |

## 3.3 finish_reason 标准枚举清单
| 枚举值 | 业务含义 | 配套特征 | 业务处理逻辑 |
|--------|----------|----------|--------------|
| `tool_calls` | 模型生成完整工具调用指令，主动终止文本输出 | message.tool_calls 存在，content 一般为 null | 执行本地工具，将工具结果以 role=tool 消息回填对话，继续请求 LLM |
| `stop` | 自然完成文本回答，无工具调用 | tool_calls 为空/null，content 存在有效文本 | 直接返回文本给终端用户，本轮对话结束 |
| `length` | 触发 max_tokens / 上下文窗口上限，输出截断 | 内容/工具参数不完整，可能半截 JSON | 拒绝执行工具，提示用户内容超限或分段续写 |
| `content_filter` | 安全风控拦截，输出违规 | content、tool_calls 均无有效可用数据 | 记录风控日志，返回合规提示，禁止重试 |
| `error` | 厂商扩展值，推理异常中断 | 无有效输出 | 上报异常，可有限次数重试 |

## 3.4 完整工具调用响应示例（非流式）
```json
{
  "id": "chat-xxxxxx",
  "object": "chat.completion",
  "created": 1789600000,
  "model": "doubao-pro",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "content": null,
        "tool_calls": [
          {
            "id": "call_001",
            "type": "function",
            "function": {
              "name": "get_weather",
              "arguments": "{\"city\":\"上海\"}"
            }
          }
        ]
      },
      "finish_reason": "tool_calls"
    }
  ],
  "usage": {
    "prompt_tokens": 210,
    "completion_tokens": 32,
    "total_tokens": 242
  }
}
```

## 3.5 正常文本回答响应示例（finish_reason=stop）
```json
{
  "id": "chat-xxxxxx",
  "object": "chat.completion",
  "created": 1789600000,
  "model": "doubao-pro",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "content": "上海今日气温 24~30℃，多云",
        "tool_calls": null
      },
      "finish_reason": "stop"
    }
  ],
  "usage": {}
}
```

# 四、流式（stream=true）特殊规范
## 4.1 流式核心规则
1. 中间分片 chunk：`finish_reason: null`，仅增量推送 `content` / `tool_calls` 分片；
2. 最后一条业务 chunk 填充真实 `finish_reason`；
3. 流末尾单独推送 `data: [DONE]`，标志流结束；
4. tool_calls 在流式中会分片拼接 id、name、arguments，需本地缓存完整后再解析。

## 4.2 流式工具调用分片示例
```
data: {"id":"chat-xxx","choices":[{"index":0,"delta":{"role":"assistant","tool_calls":[{"index":0,"id":"call_001","type":"function","function":{"name":"get_weather"}}]}],"finish_reason":null}

data: {"id":"chat-xxx","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"city\""}}]}],"finish_reason":null}

data: {"id":"chat-xxx","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\":\"上海\"}"}}]}],"finish_reason":"tool_calls"}

data: [DONE]
```

# 五、工具结果回填规范（多轮对话闭环）
执行完工具后，需要新增一条 `role: tool` 消息传入下一轮请求，绑定对应 tool_call id：
```json
{
  "role": "tool",
  "tool_call_id": "call_001",
  "name": "get_weather",
  "content": "上海今日气温24~30℃，多云，微风3级"
}
```

# 六、业务判断标准逻辑（伪代码）
```python
choice = response.choices[0]
msg = choice.message

# 分支1：需要执行工具调用（标准结构化字段，优先判断）
if choice.finish_reason == "tool_calls" and msg.tool_calls is not None:
    for call in msg.tool_calls:
        func_name = call.function.name
        args = json.loads(call.function.arguments)
        # 本地匹配注册的工具并执行
        tool_result = execute_tool(func_name, args)
        # 组装 tool 消息回填对话上下文

# 分支2：正常文本回答，直接输出
elif choice.finish_reason == "stop":
    return msg.content

# 分支3：截断异常
elif choice.finish_reason == "length":
    return "内容超出长度限制，生成已截断"

# 分支4：安全拦截
elif choice.finish_reason == "content_filter":
    return "内容存在安全风险，无法生成回答"

# 其他未知终止状态
else:
    return "模型推理异常中断"
```

# 七、兼容方案说明（多模型网关场景）
## 7.1 两种工具调用模式区分
1. **标准模式（推荐，商用大模型默认）**
    请求传 `tools`，响应返回独立 `tool_calls` + `finish_reason=tool_calls`，无文本内嵌 JSON，解析稳定，无兼容成本。
2. **兜底兼容模式（仅老开源模型降级使用）**
    不支持原生 tool call 的模型，无法返回 tool_calls 字段，永远 `finish_reason=stop`，需要通过 Prompt 约束模型在 `content` 输出 JSON，代码正则提取函数调用。
## 7.2 兼容优先级规范
1. 优先判断 `finish_reason == "tool_calls"` 与 `tool_calls` 数组，走结构化解析；
2. 无结构化工具字段时，启用文本正则提取兜底分支；
3. 全新业务、仅使用商用大模型可直接移除兜底兼容逻辑，降低代码维护成本。

# 八、常见踩坑注意事项
1. 不可仅判断 `tool_calls` 是否存在，必须配合 `finish_reason=tool_calls`；流式未结束分片会存在半截 tool_calls，不可执行；
2. `function.arguments` 是 JSON 字符串，不是对象，必须手动 JSON 解析；
3. 工具回填消息必须携带 `tool_call_id`，否则模型无法关联上一轮调用；
4. 触发 `length` 截断时，即使存在不完整工具参数，也禁止调用本地函数；
5. Claude 原生协议无 tool_calls 字段、字段名为 stop_reason，网关层需做协议转换映射。