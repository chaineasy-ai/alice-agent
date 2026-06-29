---
title: "Verificator — 验证器接口与双循环 Guardrail 架构"
summary: "Verificator 接口定义、GuardrailVerificatorAdapter 适配器、GuardrailToolProxy 代理，以及 PPAO + Micro-ReAct 双循环校验层设计"
read_when:
  - "理解或修改 guardrail 验证链路"
  - "添加新的 PreValidator / PostValidator"
  - "调试 Agent 工具调用被拦截或校验失败"
scope:
  - "alice-guardrail"
  - "alice-core-agent"
status: "active"
updated: "2026-06-29"
---

# Verificator — 验证器接口与双循环 Guardrail 架构

## 一、架构总览

Alice Agent 采用 **双循环架构**，Guardrail 在两层循环中分别介入：

```
PPAO MACRO LOOP                    Micro-ReAct LOOP (战术执行)
┌─────────────────────┐            ┌──────────────────────────┐
│ ① Perceive          │            │  Reason (LLM)            │
│ ② Plan              │            │     │                    │
│ ③ verifyPre  ───────┼────┐       │  Dispatch (Tool)        │
│    (Verificator)     │    │       │     │                    │
│ ④ Act ──────────────┼────┼───────┼→ microReActLoop()       │
│    └─ Micro-ReAct ──┼────┼───────┼──  └─ GuardrailToolProxy │
│ ⑤ observe           │    │       │       .invoke()          │
│ ⑥ verifyPost ───────┼────┼───┐   │     │                    │
│    (Verificator)     │    │   │   │  Observe                │
│ ⑦ reflect           │    │   │   │     │                    │
│ ⑧ loop              │    │   │   │  Reason (loop/FINISH)   │
└─────────────────────┘    │   │   └──────────────────────────┘
                           │   │
                    ┌──────┘   └──────┐
                    ▼                 ▼
           GuardrailService       GuardrailService
           (VerificatorAdapter)   (GuardrailToolProxy)
```

| 校验层 | 接口/类 | 所在模块 | 触发时机 | 失败流向 |
|--------|---------|----------|----------|----------|
| **PreVerify** (外层) | `Verificator.intercept()` → `GuardrailVerificatorAdapter` | `alice-core-agent` | Macro `verifyPre`，Plan 产出后，Act 前 | 全局 Reflection 重规划 |
| **MicroReact** (内层) | `GuardrailToolProxy.invoke()` | `alice-core-agent` | Micro-ReAct 中每次 TOOL_CALL 前 | 仅当前 Step 内重试，不改 Plan |
| **PostVerify** (外层) | `Verificator.audit()` → `GuardrailVerificatorAdapter` | `alice-core-agent` | Macro `verifyPost`，整套 Plan 执行完毕 | 全局复盘，补 Step/重写 Plan |

---

## 二、Verificator 接口

**文件**: `alice-guardrail/src/main/java/org/cland/alice/guardrail/Verificator.java`

```java
public interface Verificator {

  /** Pre-Verify: 在 Action 执行前拦截检查。返回 true=通过，false=拦截。 */
  default boolean intercept(Map<String, Object> action) {
    return true;
  }

  /** Post-Verify: 执行完成后审计结果。返回 true=通过，false=需 Revision。 */
  default boolean audit(Object stepResult) {
    return true;
  }
}
```

**设计要点**：
- 接口定义在 `alice-guardrail` 模块，对 `alice-core-agent` 无编译依赖
- 通过 `Map` / `Object` 与外部交互（避免循环依赖）
- 默认方法返回 `true`（放行），实现方选择性覆盖
- `AgentExecutor` 在 PPAO 宏循环中通过 `agent.verifyPre(action)` 和 `agent.audit(result)` 调用

---

## 三、GuardrailVerificatorAdapter

**文件**: `alice-core-agent/.../guardrail/GuardrailVerificatorAdapter.java`

桥接 `Verificator` 接口与 `GuardrailService` 的 `PreValidator`/`PostValidator` 链。

### 注册的默认验证器

```java
private void registerDefaultValidators() {
    // Pre-Validators
    guardrailService.registerPreValidator(new LogicSanityValidator());
    guardrailService.registerPreValidator(new PermissionSandboxValidator());

    // Post-Validators
    guardrailService.registerPostValidator(new HallucinationDetector());
}
```

### 调用流程

#### intercept(Map action) — PreVerify

```
intercept(action Map)
  │
  ├─ 从 Map 提取 type, target, actionId
  ├─ 构建单步 Plan: Plan.fastPath(summary, type, target)
  ├─ Plan.metadata ← {actionId, source="intercept"}
  ├─ 缓存 lastPlan（供 audit 阶段引用）
  │
  └─ GuardrailService.verifyPlan(plan)
       ├─ LogicSanityValidator     ← 死循环检测、终止保障
       └─ PermissionSandboxValidator ← 系统路径/命令黑名单
             │
       返回 AuditResult
       ├─ ALLOW        → return true
       ├─ REJECT       → return false
       └─ MANUAL_CONFIRM → return false
```

#### audit(Object stepResult) — PostVerify

```
audit(stepResult)
  │
  ├─ 类型检查: 必须是 StepResult
  ├─ convertStepResultToObsMap(sr)
  │   ├─ Finish  → status=SUCCESS, rawData=answer
  │   ├─ Failure → status=FAILURE, rawData=errorMessage
  │   └─ Continue → status=obs.status, rawData=obs.rawData
  │
  └─ GuardrailService.verifyResult(obsMap, lastPlan)
       └─ HallucinationDetector
            ├─ 空结果检测（no results found / null 等）
            ├─ 错误模式检测（error: / exception: / timeout 等）
            └─ 类型一致性（TOOL_CALL 应有数据，LLM_INFERENCE 不应为空）
```

---

## 四、GuardrailToolProxy

**文件**: `alice-core-agent/.../guardrail/GuardrailToolProxy.java`

**Proxy Pattern** — 在 `ExecutionEngine.invoke()` 的前后插入 Guardrail 检查。运行在 Micro-ReAct 内层循环中，每个 TOOL_CALL 独立调用。

### 注册的默认验证器

```java
public static GuardrailToolProxy createDefault(ToolRegistry registry, ExecutionEngine engine) {
    GuardrailService gs = new GuardrailService();

    // Pre-Validators
    gs.registerPreValidator(new ToolExistenceValidator(registry));
    gs.registerPreValidator(new ToolMicroLoopValidator(registry));

    // Post-Validators
    gs.registerPostValidator(new ToolResultValidator(registry));

    return new GuardrailToolProxy(engine, gs, loopValidator);
}
```

### 调用流程

```
proxy.invoke(toolName, params)
  │
  ├─ Phase 1: Pre-check
  │   ├─ 构建单步 Plan: Plan{TOOL_CALL, toolName, params}
  │   └─ GuardrailService.verifyPlan(plan)
  │        ├─ ToolExistenceValidator   ← 工具名在 ToolRegistry 中是否存在
  │        └─ ToolMicroLoopValidator   ← 跨周期微循环检测（有状态）
  │
  ├─ Phase 2: Execute
  │   └─ ExecutionEngine.invoke(toolName, params)
  │
  ├─ Phase 3: Post-check
  │   ├─ 将 ToolResult 转为 observation Map
  │   └─ GuardrailService.verifyResult(obsMap, plan)
  │        └─ ToolResultValidator   ← 风险等级标记、返回类型一致性
  │
  ├─ Phase 4: Record history
  │   └─ ToolMicroLoopValidator.recordCall(toolName, params)
  │
  └─ Return ToolResult
```

### 与 AgentExecutor 的集成

```java
// AgentExecutor.java — dispatchToolCall()
ToolResult result;
if (guardrailToolProxy != null) {
    result = guardrailToolProxy.invoke(action.target(), action.parameters());
} else {
    result = executionEngine.invoke(action.target(), action.parameters());
}
```

注入方式：

```java
GuardrailToolProxy proxy = GuardrailToolProxy.createDefault(toolRegistry, executionEngine);
executor.withGuardrailToolProxy(proxy);
```

---

## 五、全部验证器清单

### Pre-Validators（外层 PPAO `verifyPre`）

| 验证器 | 注册位置 | 校验内容 | 对应 Case |
|--------|----------|----------|-----------|
| `LogicSanityValidator` | `GuardrailVerificatorAdapter` | Plan 死循环检测（连续重复 step > 3）、终步骤缺失检测 | Case 4 |
| `PermissionSandboxValidator` | `GuardrailVerificatorAdapter` | 系统路径黑名单（/etc/、/proc/ 等）、高危命令（rm -rf / 等） | Case 8, 9 |

### Pre-Validators（内层 Micro-ReAct `GuardrailToolProxy`）

| 验证器 | 注册位置 | 校验内容 | 对应 Case |
|--------|----------|----------|-----------|
| `ToolExistenceValidator` | `GuardrailToolProxy.createDefault` | TOOL_CALL target 是否在 ToolRegistry 中注册 | Case 16-20 |
| `ToolMicroLoopValidator` | `GuardrailToolProxy.createDefault` | Plan 内同一工具 ≥3 次、跨周期精确重复 ≥3 次、跨周期总调用 >10 次 | Case 14, 30, 33 |

### Post-Validators（外层 PPAO `verifyPost`）

| 验证器 | 注册位置 | 校验内容 | 对应 Case |
|--------|----------|----------|-----------|
| `HallucinationDetector` | `GuardrailVerificatorAdapter` | 空结果关键字、错误模式关键字、TOOL_CALL/LLM_INFERENCE 类型一致性 | Case 35 |

### Post-Validators（内层 Micro-ReAct `GuardrailToolProxy`）

| 验证器 | 注册位置 | 校验内容 | 对应 Case |
|--------|----------|----------|-----------|
| `ToolResultValidator` | `GuardrailToolProxy.createDefault` | HIGH 风险工具失败 → MANUAL_CONFIRM、返回类型与声明 returnType 一致性 | Case 31, 32 |

---

## 六、Case 覆盖矩阵

设计文档 `docs/alice-guardrail/双循环Agent架构·全量原子校验Case清单.md` 定义了 46 个校验 Case。当前覆盖情况：

| 层级 | 总 Case | 已覆盖 | 未覆盖 |
|------|---------|--------|--------|
| **PreVerify** (外层) | 15 | Case 4, 8, 9 | Case 1-3(任务对齐), 5-7(依赖拓扑), 10-12(成本), 13(粒度), 14(重复), 15(风险批量) |
| **MicroReact** (内层) | 18 | Case 16-20(结构), 30(重复), 31(返回), 33(熔断) | Case 21-23(细粒度权限), 24(注入), 25(频次), 26(范围), 27-28(语义), 29(依赖), 32(冲突) |
| **PostVerify** (外层) | 10 | Case 35(信息完备) | Case 34(闭环), 36(置信度), 37-38(一致性), 39(预期对标), 40(分支), 41(副作用), 42(约束), 43(复盘) |
| **全局通用** | 3 | Case 44(熔断) | Case 45(脱敏), 46(标准化打标) |

---

## 七、扩展指南

### 添加新的 PreValidator

```java
// 1. 实现 PreValidator 接口
public class MyValidator implements PreValidator {
    @Override
    public AuditResult check(Plan plan) {
        // 校验逻辑
        if (/* 不通过 */) {
            return AuditResult.reject("原因", CorrectionSuggestion.replan("建议"));
        }
        return AuditResult.allow();
    }
}

// 2. 注册到对应的 GuardrailService
// 外层 PPAO:
adapter.guardrailService().registerPreValidator(new MyValidator());
// 内层 Micro-ReAct:
proxy.guardrailService().registerPreValidator(new MyValidator());
```

### 添加新的 PostValidator

```java
// 1. 实现 PostValidator 接口
public class MyPostValidator implements PostValidator {
    @Override
    public AuditResult check(Map<String, Object> observationMap, Plan originalPlan) {
        // 校验逻辑
        return AuditResult.allow();
    }
}

// 2. 注册
proxy.guardrailService().registerPostValidator(new MyPostValidator());
```

---

## 八、相关文件

| 文件 | 模块 | 用途 |
|------|------|------|
| `Verificator.java` | `alice-guardrail` | 验证器接口定义 |
| `GuardrailService.java` | `alice-guardrail` | 验证链核心服务 |
| `PreValidator.java` | `alice-guardrail` | 预执行验证器接口 |
| `PostValidator.java` | `alice-guardrail` | 执行后验证器接口 |
| `AuditResult.java` | `alice-guardrail` | 审计结果状态机 |
| `CorrectionSuggestion.java` | `alice-guardrail` | 修正建议类型 |
| `GuardrailVerificatorAdapter.java` | `alice-core-agent` | Verificator → GuardrailService 适配器 |
| `GuardrailToolProxy.java` | `alice-core-agent` | 工具调用守卫代理 (Proxy Pattern) |
| `LogicSanityValidator.java` | `alice-guardrail` | 逻辑闭环/死循环检测 |
| `PermissionSandboxValidator.java` | `alice-guardrail` | 权限沙箱/路径黑名单 |
| `HallucinationDetector.java` | `alice-guardrail` | 幻觉检测/关键字模式 |
| `ToolExistenceValidator.java` | `alice-guardrail` | 工具存在性校验 |
| `ToolMicroLoopValidator.java` | `alice-guardrail` | 微循环检测（有状态） |
| `ToolResultValidator.java` | `alice-guardrail` | 工具结果元数据校验 |
| `双循环Agent架构·全量原子校验Case清单.md` | `docs/alice-guardrail` | 设计文档：46 个校验 Case |
