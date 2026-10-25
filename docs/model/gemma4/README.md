---
title: Gemma4 E2E / 契约断言 —— 指针（真源唯一）
summary: 本目录不再存放测试代码副本；真源在 e2e/gemma4/。此处只保留指针与失效判据。
read_when:
  - "想跑 Gemma4 契约断言（404 model_not_found / 别名正控 / SSE chunk 规范）"
  - "想确认「哪一份才是真的」，或发现有人拿 docs 里的旧副本当真源"
  - "要判断某条 gemma4 断言是否已失效（四条失效判据）"
  - "遇到 gemma4 用例整类 skip / 全绿 却怀疑没验证到"
---

# Gemma4 E2E / 契约断言 —— 指针

**真源（唯一）：**

| 内容 | 路径 |
|---|---|
| 契约断言用例（404 `model_not_found` / 别名正控 / SSE chunk 规范） | [`e2e/gemma4/gemma4_e2e_test.py`](../../../e2e/gemma4/gemma4_e2e_test.py) |
| 轻量 OpenAI 兼容客户端 | [`e2e/gemma4/gemma_4_client.py`](../../../e2e/gemma4/gemma_4_client.py) |
| 独立契约探针（只读取证） | [`e2e/probe_gemma4_contract.py`](../../../e2e/probe_gemma4_contract.py) |
| 服务侧契约与已知缺口（HERE BE NOT · 单模型垫片） | `~/.agents/skills/cland-llm-text/SKILL.md` §8 |

## 为什么这里不再有副本（2026-10-26 · #766）

本目录原本是 `e2e/gemma4/` 的**整份副本**（`af01614` "move gemma4 test to docs"）。`#766` 把断言从
「只要报错就算过」收紧成「404 + `code=model_not_found`」、并增加了别名正控与「服务不可达默认判红」之后，
**副本没跟上**：

```
grep -c model_not_found    e2e/gemma4/gemma4_e2e_test.py = 5      docs/model/gemma4/gemma4_e2e_test.py = 0
grep -c "raise unittest.SkipTest"                          docs 副本仍有（不可达 ⇒ 整类 skip ⇒ 假绿）
```

⇒ 谁照 docs 那份跑或拷，就把修复全部撤销，而且"文档里就是这么写的"。
按 **真源唯一**：副本删除，只留本指针。

## 失效判据（照这份跑之前先自查）

1. 「未知 model」用例若**把 `Timeout`/`ConnectionError` 也算通过**，或放宽成"只要报错就行" ⇒ 断言失效；
2. 断言**不得硬编码** `gemma-4-26b-a4b`（服务侧用 `GEMMA4_SERVED_MODEL_ID` 可改名）；
3. **不得反向断言**"别名表外必须被拒"（会锁死服务侧扩展自由）；
4. 服务不可达时**默认应判红**（契约未被验证 ≠ 通过）；只有显式 `GEMMA4_E2E_SKIP_IF_DOWN=1` 才允许跳过，
   且汇总行必须显示 `skipped=N`。
