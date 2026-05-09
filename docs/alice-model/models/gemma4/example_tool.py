import json

from gemma_4_client import OpenAICompatibleClient

# 1. 初始化本地模型客户端
client = OpenAICompatibleClient(
    base_url="http://192.168.1.14:10303/v1",
    api_key="",  # 本地模型无需密钥
)

# 2. 定义工具（必须严格告诉模型可用工具）
tools = [
    {
        "type": "function",
        "function": {
            "name": "get_weather",
            "description": "查询指定城市的天气",
            "parameters": {
                "type": "object",
                "properties": {
                    "city": {"type": "string", "description": "城市名称，例如：北京"},
                    "date": {
                        "type": "string",
                        "description": "查询日期，格式：YYYY-MM-DD",
                    },
                },
                "required": ["city", "date"],
            },
        },
    }
]

# 3. 用户提问：查询今天北京天气
messages = [{"role": "user", "content": "帮我查一下今天北京的天气"}]

# 4. 带工具调用的非流式请求
resp = client.chat_completions(
    model="gemma-4",
    messages=messages,
    tools=tools,
    tool_choice="auto",  # 自动判断是否调用工具
    stream=False,
    max_tokens=500,  # 限制输出长度
)

# 5. 解析并打印 工具调用结果
assistant_msg = resp["choices"][0]["message"]
print("模型返回消息：")
print(json.dumps(assistant_msg, ensure_ascii=False, indent=2))

# 6. 提取标准工具调用（完全匹配你要的格式）
if assistant_msg.get("tool_calls"):
    tool_call = assistant_msg["tool_calls"][0]
    tool_name = tool_call["function"]["name"]
    tool_params = json.loads(tool_call["function"]["arguments"])

    print("\n=== 提取的工具调用 ===")
    print(
        json.dumps(
            {"name": tool_name, "parameters": tool_params}, ensure_ascii=False, indent=2
        )
    )
