---
title: "alice-model 配置精简参考"
summary: "~/.alice/model.json 快速参考 — providers.<name>.available_models[] 结构"
read_when:
  - "快速查阅配置结构"
  - "复制场景配置片段"
---

# llm_config.json 精简参考

> 完整说明请参见 [CONFIG.md](./CONFIG.md)。

## 配置结构

```
~/.alice/model.json
├── default_model      对象 { provider, model, enable_thinking, reasoning_effort }
├── planner            双路径模型 ID + thinking 参数
└── providers          凭据 + available_models[]
```

## 场景配置片段

### TAO Think 规划（慢思考）
```json
{
  "model": "deepseek-v4-flash",
  "enable_thinking": true,
  "reasoning_effort": "high"
}
```

### TAO Observe 快思考（关闭 LLM 思考）
```json
{
  "model": "deepseek-v4-flash",
  "enable_thinking": false,
  "reasoning_effort": "low"
}
```

## 映射规则速查

| 供应商 | enable_thinking | API 输出 |
|--------|----------------|----------|
| DeepSeek | `false` | `thinking:{"type":"disabled"}` |
| DeepSeek | `true` + `effort` | `thinking:{"type":"enabled", "effort":"..."}` |
| OpenAI o 系列 | `false` | 强制 `reasoning_effort="low"` |
