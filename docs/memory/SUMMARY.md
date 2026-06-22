# 技术设计文档：默认记忆提炼器（DefaultMemorySummarizer）

## 1. 业务背景与定位

在 `Alice Agent` 的智能体运行架构中，**The Consolidation Process（记忆固化机制）** 负责将短期的、碎片化的会话追踪（Session Trace）转化为长期的、可复用的结构化记忆。

`DefaultMemorySummarizer` 是该机制的核心入口。它作为 `MemorySummarizer` 接口的默认实现，旨在无监督状态下，从原始会话 Trace 中提炼出两类高价值信息：

* **Facts（事实性陈述）**：沉淀单步执行中高价值的确定性结论（What learned）。
* **Success Patterns（成功模式）**：捕捉连续成功的动作序列，形成可沉淀的最佳实践路径（How to succeed）。

---

## 2. 数据模型抽象

在进行提炼前，系统对输入与输出建立如下契约模型：

### 2.1 输入模型：`Step` (单步追踪)

提炼器依赖的核心元数据包含：

* **`stepId`**: 包含生命周期标识的唯一键（格式通常为 `${sessionId}::${stepUuid}`）。
* **`success`**: 布尔值，标记该步骤是否最终执行成功。
* **`importance`**: 浮点数（$0.0 \sim 1.0$），代表模型或评估器对该步骤重要性的打分。
* **`action` / `input` / `output**`: 执行的原子能力名称、输入参数及响应结果。

### 2.2 输出模型：`Summary` (记忆简报)

提炼后生成的结构化上下文：

* **`sessionId`**: 归属的会话标识。
* **`facts`**: 提炼出的事实性陈述列表（`List<String>`）。
* **`successPatterns`**: 抽象出的成功动作链路列表（`List<String>`）。
* **`stepCount`**: 本次处理的原始步骤总数。

---

## 3. 核心机制与算法设计

提炼器内部采用双轨并行处理引擎，分别对 Facts 和 Success Patterns 进行提取。

### 3.1 事实提取引擎（Facts Extractor）

事实提取遵循“高质量过滤”**与**“唯一性去重”原则。

1. **合法性与成功过滤**：仅对执行成功（`success == true`）且有效的步骤进行分析。
2. **重要度剪枝（Pruning）**：通过设定动态阈值 $Threshold_{importance} = 0.3$，剔除无意义的中间冗余步骤（如简单的格式对齐、参数校验等低信息量步骤）。
3. **语义去重（De-duplication）**：以 `action + input` 组装成高维特征 Key。在单次会话中，若相同的操作和输入出现多次，仅保留首次发生的步骤。
4. **输出截断（Truncation）**：为防止长文本或大文件污染记忆库，提取引擎对 `output` 进行滑动窗口截断，仅保留前 $100$ 个字符作为快照预览。

### 3.2 模式提取引擎（Success Pattern Extractor）

成功模式提取用于捕获 Agent 的“连击（Combo）”路径。为了避免复杂的双指针带来的状态维护风险（指针越界、坏数据造成的指针悬挂），引擎采用“容器断流机制（Container-Based Stream Breaker）”。

```
[Trace Flow] -> [Step 1: S] -> [Step 2: S] -> [Step 3: S] -> [Step 4: F]
                    |             |             |               |
[Container]  -> [ Step 1 ] -> [Step1, 2] -> [Step1, 2, 3]       |
                                                                v
[Trigger]    --------------------------------------------> [Size(3) >= 3] -> [Flush Pattern] -> [Clear]

```

#### 算法状态机流程：

1. **状态初始化**：创建动态有序容器 `currentRun`。
2. **流式遍历**：
* **遇成功步骤**：将步骤顺序追加至 `currentRun` 中，维持序列拓扑结构。
* **遇失败步骤/异常数据**：立即触发**链路断流结算（Stream Break）**。


3. **断流结算逻辑**：
* 检查 `currentRun` 容器长度。若满足最小连续成功长度（$MIN\_RUN = 3$），则将容器内的动作序列转化为有向图拓扑文本（例如：`ActionA → ActionB → ActionC`），作为 Success Pattern 沉淀。
* **严格清空（Clear）**：无论是否满足结算条件，清空 `currentRun`，彻底切断状态污染，开始迎接下一段序列。


4. **尾部兜底**：遍历结束后，对容器进行最后一次边界检查与结算，确保末尾的连续成功序列不丢失。

---

## 4. 防御性与鲁棒性设计

为确保系统作为底层高并发组件的稳定性，设计文档强制约束以下容错处理：

### 4.1 会话 ID 自愈（Session ID Resolution）

由于智能体在集群或多线程环境下运行时，`stepId` 可能缺失或不符合 `${sessionId}::${stepUuid}` 标准。

* **左边界检查**：当分隔符 `::` 处于字符串首位时，判定为非法资产，放弃裁剪，降级使用完整 `stepId`。
* **空值兜底**：若 `stepId` 全空，提炼器自动降级采用 `session-currentTimeMillis` 生成时间戳标记，防止后续流转发生 `NullPointerException`。

### 4.2 极值容错与未定义行为（NPE Architecture）

* **节点全空防护**：对 Trace 中的任何 `null` 节点提供静默跳过机制，并在流式遍历中将 `null` 视同为 `success == false` 的断流信号，确保容器内部绝对干净。
* **未定义属性降级**：当 `action` 或 `input` 偶发性返回空时，采用 `UNKNOWN_ACTION` 和 `UNKNOWN_INPUT` 进行文本降级替换，保证语义层面的可复读性。

---

## 5. 配置参数矩阵

为保证系统的可扩展性，以下控制参数收拢于组件边界，后续支持通过微服务中心或策略配置注入：

| 参数名称 | 默认值 | 作用域 | 业务含义 |
| --- | --- | --- | --- |
| `MIN_SUCCESS_RUN` | `3` | 成功模式提取 | 构成一个可复用“最佳实践模式”的最小连续成功步骤数。 |
| `FACT_IMPORTANCE_THRESHOLD` | `0.3` | 事实陈述提取 | 低于此重要度打分的执行步骤将被过滤，不视作“事实”。 |
| `OUTPUT_PREVIEW_LENGTH` | `100` | 事实陈述提取 | 记忆库中沉淀的事实结果快照的最大字符截断长度。 |
| `SESSION_SEPARATOR` | `"::"` | 全局解析 | 拆解链路追踪中会话 ID 与单步 ID 的核心分隔符。 |
