在这套 **PPAO (Perceive-Plan-Act-Observe) + Guardrail** 架构下，如果要让 Agent 完成一个“编写前端页面”**的场景，整个提示词流（Prompt Stream）将不再是简单的一问一答，而是伴随着宏观规划、微观执行和强校验的**多层次提示词流转过程。

由于使用了 **Vert.x**，整个过程在工程上是由事件驱动（Event-Driven）的异步 `Future` 或 `Polymer` 响应式流来承载。

以下是该场景下核心的提示词流设计与流转：

---

## 1. 整体提示词流转全景

在编写前端页面时，提示词流分为三个级别：**System Context（系统基调）**、**Strategic Plan（宏观规划）** 和 **Tactical ReAct（微观执行与观察）**。

---

## 2. PPAO 核心节点的提示词流深度拆解

### 阶段一：Perceive (感知)

* **输入信号**：用户原始输入（如：“帮我写一个仿 Notion 的看板组件，支持拖拽和暗黑模式切换”）。
* **Vert.x 行为**：从 `MemoryVault` 异步获取当前技术栈（如：React + Tailwind）、从 `EnvAdapter` 获取当前沙箱的项目结构。
* **流转提示词 (无 LLM 交互，纯结构化组装)**：
```markdown
[CONTEXT ASSEMBLY]
- User Request: "帮我写一个仿 Notion 的看板组件..."
- Tech Stack: React 18, Tailwind CSS, TypeScript
- Existing Files: src/App.tsx, package.json

```



---

### 阶段二：Plan (宏观规划)

* **Vert.x 事件**：触发 `Planner` 的 Vert.x EventBus 消息。
* **PLANNER_PROMPT**：
> **Role**: 前端资深架构师
> **Task**: 将用户的复杂前端需求，分解为具备依赖关系的阶段性里程碑（Sub-Goals）。
> **Context**: {上述感知到的系统上下文}
> **Output Format (JSON)**: `Array<{stepId: number, goal: string, expectedOutput: string}>`


* **LLM 输出 (StrategicGoal)**：
```json
[
  {"stepId": 1, "goal": "设计看板的数据结构与 TypeScript Type 定义", "expectedOutput": "types.ts"},
  {"stepId": 2, "goal": "实现 Column 和 Card 的基础 UI 组件（支持 Tailwind 暗黑模式）", "expectedOutput": "components/Card.tsx"},
  {"stepId": 3, "goal": "引入拖拽库并实现列间/列内拖拽逻辑", "expectedOutput": "components/Board.tsx"}
]

```



---

### 阶段三：Verify Pre (行动前审计拦截)

* **Vert.x 行为**：`Guardrail` 拦截器订阅了规划流，审查步骤 1 是否合规。
* **GUARDRAIL_PRE_PROMPT**：
> 审查以下生成的目标是否存在安全隐患、技术栈冲突或逻辑死循环：
> Target: "设计看板的数据结构与 TypeScript Type 定义"
> 如果通过，返回 `{"status": "PASS"}`。



---

### 阶段四：Act & Observe (微观 Tactical ReAct 闭环)

进入核心编码阶段。这里采用 **Micro-ReAct Loop**，Prompt 会随着每一次 Tool（如文件写入、组件渲染、Vite 编译）的返回而动态变长。

#### **轮次 4.1：Reasoning (思考与决策生成)**

* **ACTOR_REACT_PROMPT**：
> **Role**: 前端专家编码器
> **Current Strategic Goal**: 设计看板的数据结构并创建 types.ts
> **Workspace**:
> * `src/types.ts` (Not exist)
> 
> 
> **Available Tools**: `writeFile(path, content)`, `readFile(path)`, `runCommand(cmd)`
> **Instructions**: 你必须严格按照以下格式思考和行动：
> Thought: 思考你需要做什么。
> Action: 调用工具。
> Observation: 等待工具返回结果。
> ... (Repeat until done)


* **LLM 给出 Action**：
```markdown
Thought: 我们需要先定义 Card 和 Column 的接口，并导出它们。
Action: writeFile{"path": "src/types.ts", "content": "export interface Card { id: string; title: string; ... }"}

```



#### **轮次 4.2：Execution & Observation (工具执行与观察)**

* **Vert.x 行为**：`ToolGateway` 接收到 `writeFile` 请求，异步写入文件系统。随后，自动触发 `runCommand{"cmd": "npm run type-check"}` 进行编译。
* **Observation（工具反馈）**：
```text
[Command Output]: src/types.ts:4:12 - error TS2304: Cannot find name 'IdType'.

```


* **Prompt 流动态追加 (In-loop Update)**：
将上述错误作为 `Observation` 直接追加到 Context 中，再次喂给 Actor。
* **Actor 的新一轮 Thought**：
```markdown
Thought: 刚才定义的类型中，`IdType` 未定义，应该直接使用 `string`。我需要修正 `src/types.ts`。
Action: writeFile{"path": "src/types.ts", "content": "..."}

```



---

### 阶段五：Verify Post (行动后自省与静态检查)

当 Actor 认为任务 1 完成并输出 `Finish` 后，代码进入 `Guardrail.auditFinalResult`。

* **VERT.X 管道流水线**：
1. **静态代码检查**：Vert.x 直接调用底层工具链（ESLint / TSC）。
2. **LLM 语义校验 (GUARDRAIL_POST_PROMPT)**：
> **Task**: 审查生成的代码是否满足原定目标、是否存在潜在 Bug。
> **Plan**: "实现 Column 和 Card 的基础 UI 组件（支持 Tailwind 暗黑模式）"
> **Generated Code**: `{Card.tsx 的内容}`
> **Question**: 代码中是否确实包含了 Tailwind 的 `dark:` 变体类？如果没有，请判定为 FAIL 并给出修改意见。





---

### 阶段六：Reflect (战略复盘与记忆沉淀)

* **场景 1 (成功)**：如果 Post-Verify 通过，触发 **REFLECT_PROMPT**，让 LLM 总结当前经验（如：“已成功建立 UI 基础，Card 组件暴露出 `onDragStart` 接口”），写入 `MemoryVault`，然后推进到宏观 Plan 的 `stepId: 3`。
* **场景 2 (失败触发 REVISION)**：如果页面在浏览器渲染时报错（样式崩塌），Post-Verify 拦截，带着报错信息回退到 `PLANNING` 状态，提示词流将变成：“上一次尝试由于 Tailwind 配置未生效导致样式丢失，请重新规划修复路径。”

---
