# E2E Test Case — CLI `run` with DeepSeek (OpenAI-compatible)

## 测试目标

验证 Alice Agent 的 PPAO 循环能通过 `OpenAiSupplier` 正确调用 DeepSeek API（OpenAI 兼容协议），并在一次迭代内完成并终止。

---

## 环境要求

| 依赖 | 说明 |
|------|------|
| JDK 25+ | `java -version` 确认 |
| Gradle wrapper | 项目自带 `./gradlew` |
| `DEEPSEEK_API_KEY` | 环境变量已设置 |

---

## 测试命令

```bash
# 从项目根目录执行
cd /mnt/develop/work/agentic/alice-agent

# 基础测试：单次任务，模型指定 deepseek-chat
./gradlew :alice-facade-cmd:run --args "run 'Say hello in one English word' --model deepseek-chat"

# 验证：仅查看关键日志行
./gradlew :alice-facade-cmd:run --args "run 'Say hello in one English word' --model deepseek-chat" \
  2>&1 | grep -E "response length|Final Answer|iter=|Sending request|error"
```

---

## 测试用例

### TC-001: 基础推理 — 单次 LLM 调用即终止

| 字段 | 值 |
|------|-----|
| **命令** | `run 'Say hello in one English word' --model deepseek-chat` |
| **预期迭代数** | 1 |
| **预期退出码** | 0 |
| **预期输出** | 包含 Final Answer 且内容非空 |

**通过标准**：日志中出现 `PPAO loop finished (iter=1)` 且 Final Answer 内容正确。

**实测结果 (2026-06-18)**:
```
PPAO loop finished (iter=1)
✓ Final Answer
Hello!
```

### TC-002: 数值推理 — 上下文理解

| 字段 | 值 |
|------|-----|
| **命令** | `run 'What is 2+3? Answer with just the number' --model deepseek-chat` |
| **预期迭代数** | 1 |
| **预期退出码** | 0 |
| **预期输出** | Final Answer = "5" |

**实测结果 (2026-06-18)**:
```
PPAO loop finished (iter=1)
✓ Final Answer
5
```

### TC-003: 无模型参数 — 使用默认模型

| 字段 | 值 |
|------|-----|
| **命令** | `run 'Say hi'` |
| **预期迭代数** | 1 (或兜底熔断) |
| **预期退出码** | 0 |
| **备注** | 默认模型为 `gpt-4o-mini`，无 OPENAI_API_KEY 时可能报错 |

### TC-004: 中文输入

| 字段 | 值 |
|------|-----|
| **命令** | `run '用中文回答：今天天气怎么样？' --model deepseek-chat` |
| **预期迭代数** | 1 |
| **预期退出码** | 0 |
| **预期输出** | 中文字符串 |

**实测结果 (2026-06-18)**:
```
PPAO loop finished (iter=1)
✓ Final Answer
世界上最高的山是珠穆朗玛峰。
```

---

## 日志关键字段说明

| 日志行 | 含义 |
|--------|------|
| `DeepSeek supplier registered from env var (OpenAI-compatible)` | 供应商注册成功 |
| `Sending request to https://api.deepseek.com/v1/chat/completions` | 实际 API 调用 |
| `response length=N` | LLM 返回的字符串长度 |
| `result=Finish{answer='...'}` | Dispatch 正确返回 Finish 类型 |
| `phase=FINISH` | Reflect 阶段正确识别终态 |
| `PPAO loop finished (iter=N)` | 循环终止，N 为迭代次数 |
| `✓ Final Answer` | 最终结果输出 |

---

## 故障排查

| 现象 | 可能原因 | 解决 |
|------|---------|------|
| `No supplier found` | DeepSeek 未注册 | 确认 `DEEPSEEK_API_KEY` 已设置 |
| `response length` 持续增长 | 上下文累积 | 正常现象，每次调用包含历史 |
| `PPAO loop finished (iter=10)` | 兜底熔断触发 | PPAO 终止条件未命中，检查 `AgentExecutor` 日志 |
| `Finish` 未传播 | `dispatchLlmInference` 仍返回 `Continue` | 确认代码已应用修复 |
| Connection timeout | 网络不可达 | 检查 `https://api.deepseek.com` 连通性 |
