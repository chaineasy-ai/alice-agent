# Case: PPAO 循环永不终止 — AgentExecutor 无限推理故障

## 概述

| 字段 | 值 |
|------|-----|
| **分类** | 执行引擎 / PPAO 循环 / AgentExecutor |
| **严重性** | Critical — Agent 无法完成任何自然语言任务 |
| **发现日期** | 2026-06-18 |
| **修复版本** | v0.1.0 |
| **相关模块** | `alice-core-agent` |
| **涉及文件** | `AgentExecutor.java` |

---

## 现象

CLI 命令 `alice run "Say hello" --model deepseek-chat` 进入无限循环。

典型日志输出：

```
[Plan] iteration=1
[Act] entering Micro-ReAct loop
[Micro-ReAct/LLM] response length=6
[Observe] result=Continue{action=Action{id='...', type=FINISH, target='FINISH'}}
[Verify/Post] audit passed
[Reflect] phase=VERIFYING_POST
[Plan] iteration=2
[Act] entering Micro-ReAct loop
[Micro-ReAct/LLM] response length=6
...
```

Agent 反复调用 LLM，每一次都拿到有效答复，但从不退出循环，直到达到 `maxIterations=10` 的兜底熔断才强行结束。

---

## 根因分析

通过日志追踪，发现 PPAO 循环中存在 **3 个独立 Bug** 叠加导致永不终止：

### Bug 1：`dispatchLlmInference` 返回 `Continue` 而非 `Finish`

在 Micro-ReAct 的 Dispatch 阶段，LLM 成功返回后，代码将结果包装为：

```java
// ❌ 返回 Continue(action=FINISH) — shouldFinish 不识别
return new StepResult.Continue(Action.finish(), Observation.success(content));
```

`StepResult.Continue` 语义是"继续执行"，即使内部的 `Action` 是 `FINISH` 类型，`shouldFinish()` 方法也**只检查** `result instanceof StepResult.Finish`，因此永远返回 `false`。

**受影响的 dispatch 方法**：

| 方法 | 成功路径 | 失败路径 |
|------|---------|---------|
| `dispatchLlmInference` | ❌ `Continue(FINISH)` → `Finish(content, msg)` | ❌ `Continue(revision)` → `Failure(msg)` |
| `dispatchToolCall` | ✅ `Continue(LLM_INFERENCE)` — 正确（需要继续推理） | ❌ `Continue(revision)` → `Failure(msg)` |

### Bug 2：`verifyPost` 不识别 `Finish`/`Failure`

当上一步返回 `StepResult.Finish` 或 `StepResult.Failure` 时，`verifyPost` 没有立即短路设置 `Phase.FINISH`，而是进入 `agent.verifyPost(result)` 的审计流程。审计通过后虽然调用了 `shouldFinish()`，但当时 `result` 已被包装为其他类型，导致终止条件再次失效。

### Bug 3：`reflect` 忽略已设置的 `Phase.FINISH`

即使 `verifyPost` 成功设置 `ctx.currentPhase() = Phase.FINISH`，`reflect` 方法的第一行逻辑是：

```java
if (ctx.currentPhase() != AgentContext.Phase.FINISH) {
    ctx.transitionTo(AgentContext.Phase.REFLECTING);  // ❌ 覆盖了 FINISH
}
```

`REFLECTING` → `PLANNING` 是合法转换，于是相位被回退，下一轮 `loopBody` 中的 `shouldFinish(context, null)` 检查 `phase == FINISH` 返回 `false`，循环继续。

### Bug 4：`loopBody` 不递增迭代计数器

`incrementIteration()` 仅在 `perceive()` 中被调用一次（在 `executeLoop` 入口）。递归的 `loopBody()` 在每轮 Macro 迭代后**不递增计数器**，因此 `isMaxIterationsReached()` 永远返回 `false`，兜底熔断失效。

---

## 修复方案

### 修复 1：`dispatchLlmInference` 成功路径返回 `Finish`

```java
// ✅ 返回 Finish 直接触发 shouldFinish
return new StepResult.Finish(content, "LLM response received");
```

**原理**：`shouldFinish()` 的第一个判断就是 `result instanceof StepResult.Finish`，返回 `true` 直接终止循环。

### 修复 2：`dispatchLlmInference` 失败路径返回 `Failure`

```java
// ✅ 不再返回 Continue(revision) 循环重试
return new StepResult.Failure("LLM call failed: " + call.status());
```

**原理**：LLM 调用失败是不可恢复错误，不应进入 Revision 循环重试，应直接终止。

### 修复 3：`verifyPost` 短路处理 `Finish`/`Failure`

```java
// ✅ 在审计之前检查终态
if (result instanceof StepResult.Finish || result instanceof StepResult.Failure) {
    ctx.transitionTo(AgentContext.Phase.FINISH);
    return Future.succeededFuture(stepWithCtx);
}
```

### 修复 4：`reflect` 短路处理 `Phase.FINISH`

```java
// ✅ 在转换相位之前检查终态
if (ctx.currentPhase() == AgentContext.Phase.FINISH) {
    return Future.succeededFuture(ctx);
}
```

### 修复 5：`loopBody` 每轮递增迭代计数器

```java
// ✅ 每一轮 Macro 迭代后递增
ctx.incrementIteration();
return loopBody(ctx);
```

**原理**：确保 `isMaxIterationsReached()` 兜底熔断在设置的最大迭代次数后生效。

---

## 修复后的执行流

```
alice run "Say hello" --model deepseek-chat

[Micro-ReAct/LLM] response length=6          ← DeepSeek 返回 "Hello."
[Observe] result=Finish{answer='Hello.'}      ← Finish 正确传播
[Verify/Post] result=Finish{answer='Hello.'}  ← 短路设置 Phase.FINISH
Agent PPAO loop finished (iter=1)            ← 一次迭代即终止
✓ Final Answer [19:49:41]
```

---

## 状态机验证

修复后的 PPAO 相位转换路径：

```
START → PERCEIVING → PLANNING → VERIFYING_PRE → ACTING
  → OBSERVING → VERIFYING_POST → FINISH  ← 正确终态，不再回退
```

修复前的错误路径：

```
START → PERCEIVING → PLANNING → VERIFYING_PRE → ACTING
  → OBSERVING → VERIFYING_POST → REFLECTING → PLANNING → ...  ← 无限
```

---

## 测试覆盖

| 测试 | 覆盖场景 | 状态 |
|------|---------|------|
| `AgentExecutorSpec` (现有) | PPAO 循环基础路径 | ✅ |
| 手动 E2E: `run "Say hello" --model deepseek-chat` | 完整集成测试 | ✅ 1 次迭代 |
| 手动 E2E: `run "Hello" --model gpt-4o-mini` | 跨供应商 | ✅ |
| 兜底熔断: 模拟 LLM 持续 | `isMaxIterationsReached` 兜底 | ✅ |

---

## 经验教训

1. **`Continue` ≠ `Finish`**：`StepResult.Continue(Action.finish())` 虽然在语义上"将要结束"，但在状态机层面它仍然是 `Continue`。`shouldFinish()` 只认 `result instanceof StepResult.Finish`。两者之间的间接层是 Bug 的根源。
2. **相位转换不可逆**：一旦设置 `Phase.FINISH`，所有后续阶段（特别是 `reflect`）必须先检查终态，避免意外回退。
3. **计数器是最后防线**：迭代计数器不应该只在入口递增。递归循环必须在每次迭代后递增，否则兜底熔断形同虚设。
4. **Fail-fast**：LLM 调用失败和工具调用失败不应进入 Revision 循环重试。不可恢复错误应直接返回 `Failure` 终止循环。
