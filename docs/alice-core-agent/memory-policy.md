---
title: 会话记忆与历史——决议（memory policy）
summary: 会话记忆的边界、真源、删除路径与已知端口状态；H08 的 in-repo 决议真源。
read_when:
  - 改 /new 或 /clear 或 /context 或 /resume 的行为与回显时
  - 需要判断会话历史存哪里、用户怎么彻底删除、能不能承诺"长期记忆"时
  - 碰 AgentSession / withMemory / memory-vault / H08 接线时
---

# 会话记忆与历史 · 决议（#751 → #753）

## 1. 一句话

**没有"长期记忆"。会话历史只存在于本机 WAL 目录，能力边界是「本会话可用 + resume 可恢复」。**

## 2. 产品口径（compass 给，2026-10-25）

| 维度 | 结论 |
|---|---|
| 用户可见 | **是**，但只到「本会话 + resume」这一层 —— **不是**长期记忆 |
| 记什么 | 仅本会话的消息、工具调用与结果 |
| 不记什么 | 跨会话偏好、画像、跨设备同步 |
| 记多久 | 会话内可用；`alice resume` 可恢复；**不承诺永久、不承诺跨设备** |
| 用户能否删 | **能，且是一等能力**（见 §4） |

## 3. 真源（唯一）

- 会话历史的**唯一真源**：`~/.alice/wal/<session-id>/`（`WalSession` + `FileWalStore`）。
- `AgentSession`（内存 `Map`）是**遗留端口**，生产装配不挂（`Agent.createDefault()` 不调用 `withMemory`）。
  - 想接它 = 给同一份历史造**第二份账**，除 H08 的整体接线决议外不要动。
  - 读历史（`/context` 的消息条数、`/compact`、resume）一律**读 WAL**。
    ⚠️ 现状（2026-10-26 复核）：**`/context` 读 WAL 尚未落地**（`#753` 的验收用例
    `AgentContextViewSpec` 在 main 上是红的）。本节是**目标契约**，不是现状；
    在它落地前不要把 `/context` 的消息条数当可信数据。
- 一个会话只有一个 id：`launcher` / 命令层 / `Agent` 三者共用 `agent.sessionId()`（#752）。

## 4. 删除路径（明确写清，不含混）

| 操作 | 实际效果 |
|---|---|
| `rm -rf ~/.alice/wal/<session-id>/` | **彻底删除该会话历史，不可恢复**（当前唯一的删除方式，用户现在就能做） |
| `/clear`（交互会话内） | **只清屏**；**清上下文未实现**（`ClearContextCmd` 无调用者 = no-op，见 §5 与 `#751`）。不删 WAL 历史，历史仍可 resume |
| `/new` | 重置当前会话的界面与内存上下文；**不删 WAL 历史** |
| `alice resume --session-id <id>` | 从 WAL 恢复历史（只读回放） |
| `alice resume --delete` | **未实现**，`deferred`（见 §6）。**不要**据此说"现在删不了" |

> 一句话给用户：**要彻底删就删目录；`/clear` 不等于删历史。**

## 5. 契约护栏（防止再退化）

| 护栏 | 位置 | 变异必须红 |
|---|---|---|
| 默认装配不含短期记忆（`createDefault(...).memory() == null`） | `TuiSpec` #751 | 擅自 `withMemory(new AgentSession())` ⇒ 红 |
| 无记忆时 `/new` 只报「无内存上下文可清 + resume 入口」 | `TuiSpec` | 改回「上下文已清空」⇒ 红 |
| `/clear` 两个入口（TUI / CLI）都**不得出现「上下文已清除」**，且都要说明不删 WAL 历史 | `TuiSpec` + `AliceCliNotWiredSpec` | 任一侧出现「上下文已清除」/去掉"不删 WAL 历史" ⇒ 红（现状：TUI `屏幕已清空（上下文未清）`；CLI `[not wired]`） |
| `/context` 消息条数来自 WAL | `TuiSpec`/`AgentSpec` | 改回 `N/A` ⇒ 红 |

## 6. 未做（明确 deferred，不是"忘了"）

- `alice resume --delete`（删除命令；删除能力本身已具备 = 删目录）。
- H08：`memory-vault` → 会话记忆的整体接线（持久化/摘要/遗忘）。本文件是它的**决议入口**，
  正文不再抄进 `docs/TODO.md`（那里只留指针）。
