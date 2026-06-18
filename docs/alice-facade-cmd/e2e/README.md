# E2E Testing Guide — alice-facade-cmd

## 快速开始

```bash
# 从项目根目录
cd /mnt/develop/work/agentic/alice-agent

# 确保环境变量已设置
export DEEPSEEK_API_KEY=sk-xxxxx

# 运行基础 e2e 测试（单次任务 + DeepSeek）
./gradlew :alice-facade-cmd:run --args "run 'Say hello' --model deepseek-chat"
```

---

## 测试方式

### 方式一：Gradle `run` 任务（推荐）

直接通过 Gradle 执行 CLI，适合快速验证：

```bash
# 基础任务
./gradlew :alice-facade-cmd:run --args "run '你的任务描述' --model deepseek-chat"

# 查看精简日志
./gradlew :alice-facade-cmd:run --args "run 'Hello' --model deepseek-chat" \
  2>&1 | grep -E "response|Finish|iter=|Final|error"

# 查看全部日志
./gradlew :alice-facade-cmd:run --args "run 'Hello' --model deepseek-chat --verbose" 2>&1

# 指定其他模型
./gradlew :alice-facade-cmd:run --args "run 'Hello' --model gpt-4o-mini"
```

### 方式二：Python E2E 测试套件

```bash
# 先构建项目
./gradlew :alice-bootstrap:installDist

# 运行全部 e2e 测试
python3 e2e/run_alice_e2e.py

# 指定测试类
python3 e2e/run_alice_e2e.py TestAliceCliHelp

# 构建 + 测试
python3 e2e/run_alice_e2e.py --build
```

### 方式三：安装后直接执行二进制

```bash
# 安装到 build 目录
./gradlew installDist

# 直接执行
./alice-bootstrap/build/install/alice-agent/bin/alice run "Hello" --model deepseek-chat
```

### 方式四：单元测试

```bash
# 运行全部单元测试
./gradlew check

# 运行特定模块
./gradlew :alice-facade-cmd:test

# 运行特定测试类
./gradlew :alice-facade-cmd:test --tests "*AliceCliLauncherSpec*"
```

---

## 支持的模型供应商

| 供应商 | 环境变量 | 适配器 | 协议 |
|--------|---------|--------|------|
| OpenAI | `OPENAI_API_KEY` | `OpenAiSupplier` | OpenAI Chat Completions |
| DeepSeek | `DEEPSEEK_API_KEY` | `OpenAiSupplier` (OpenAI-compatible) | OpenAI Chat Completions |
| Anthropic | `ANTHROPIC_API_KEY` | `ClaudeSupplier` | Anthropic Messages |
| Gemma4 (本地) | `GEMMA4_BASE_URL` | `Gemma4Supplier` | OpenAI-compatible |
| 自定义 | 通过 `~/.alice/model.json` 配置 | 自动适配 | OpenAI-compatible |

DeepSeek 使用 `OpenAiSupplier` 的原因是 DeepSeek API 与 OpenAI Chat Completion 协议完全兼容，无需独立适配器。

---

## 验证 PPAO 循环正常终止

正确行为（1 次迭代即终止）：

```
[Micro-ReAct] step depth=0/10
[Micro-ReAct/LLM] response length=N
[Observe] result=Finish{answer='...'}
[Verify/Post] result=Finish{answer='...'}
[Reflect] phase=FINISH
Agent PPAO loop finished (iter=1)
✓ Final Answer [timestamp]
```

错误行为（无限循环，直至兜底熔断）：

```
[Micro-ReAct] step depth=0/10
[Micro-ReAct/LLM] response length=N
[Observe] result=Continue{action=Action{FINISH}}    ← 应为 Finish
[Verify/Post] audit passed
[Reflect] phase=VERIFYING_POST                      ← 应为 FINISH
[Plan] iteration=2
...（重复直到 iteration=10）
Agent reached max iterations (10)
```

---

## 配置供应商

### 环境变量方式（快速）

```bash
export DEEPSEEK_API_KEY=sk-xxxxxxxx
export OPENAI_API_KEY=sk-xxxxxxxx
```

Gradle 会自动继承当前 shell 的环境变量。

### 配置文件方式（持久化）

创建 `~/.alice/model.json`：

```json
{
  "language_models": {
    "openai_compatible": {
      "deepseek": {
        "api_url": "https://api.deepseek.com/v1",
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
      }
    }
  }
}
```

详细配置说明见 [docs/alice-model/CONFIG.md](../../alice-model/CONFIG.md)。

---

## 测试清单

### 基础设施

- [ ] JDK 25+ 已安装：`java -version`
- [ ] Gradle wrapper 可用：`./gradlew --version`
- [ ] API Key 已设置：`echo ${DEEPSEEK_API_KEY:0:8}...`
- [ ] 项目已编译：`./gradlew :alice-facade-cmd:compileJava`

### 功能验证

- [ ] `run` 基础任务：`run 'Hello' --model deepseek-chat` → iter=1
- [ ] `run` 数值推理：`run '2+3?' --model deepseek-chat` → answer=5
- [ ] `run` 中文输入：`run '用中文回答' --model deepseek-chat` → 中文输出
- [ ] `run` 默认模型：`run 'Hello'` → 使用 gpt-4o-mini（需 OPENAI_API_KEY）
- [ ] `run` 多轮不循环：确认 iter=1 而非 iter=10

### 回归验证

- [ ] 单元测试通过：`./gradlew check`
- [ ] Python E2E 通过：`python3 e2e/run_alice_e2e.py`

---

## 参考

| 文档 | 位置 |
|------|------|
| 设计文档 | `docs/alice-facade-cmd/DESIGN.md` |
| CLI 命令参考 | `docs/alice-facade-cmd/cmd.md` |
| 测试用例 | `docs/alice-facade-cmd/e2e/case.md` |
| 测试计划 | `docs/alice-facade-cmd/e2e/TestPlan-AliceFacadeCmd.md` |
| 模型配置 | `docs/alice-model/CONFIG.md` |
| 故障案例 | `docs/case/infinite-loop-ppao.md` |
