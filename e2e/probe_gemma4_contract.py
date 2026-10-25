#!/usr/bin/env python3
"""Gemma4 服务端「契约探针」—— 只读、可复现，用来核对服务侧契约（#765 → #766）。

背景：#765 里的 3 个预存红不是被测产品的问题，而是**用例对 gemma4 服务侧的假设**
与**服务侧实际实现**不一致。判定归属需要三件东西（缺一不可）：
  1. 服务侧**实际行为**（本脚本输出，任何人都能跑）
  2. 用例**假设的出处**（OpenAI Chat Completions 规范 / 服务文档）
  3. 服务侧的**承诺**（`cland-llm-text` 技能文档 / 服务实现 `gemma4.py`）

用法:
    python3 e2e/probe_gemma4_contract.py                  # 默认 127.0.0.1:10303
    GEMMA4_BASE_URL=http://192.168.1.14:10303/v1 python3 e2e/probe_gemma4_contract.py

退出码恒为 0：这是**取证**工具，不做 pass/fail 判定（判定归 #765 的裁决）。
"""

import json
import os
import sys
import urllib.error
import urllib.request

BASE = os.environ.get("GEMMA4_BASE_URL", "http://127.0.0.1:10303/v1").rstrip("/")
MODEL = os.environ.get("GEMMA4_MODEL", "gemma-4")
TIMEOUT = 30


def _post(path: str, payload: dict, stream: bool = False):
    req = urllib.request.Request(
        f"{BASE}{path}",
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        resp = urllib.request.urlopen(req, timeout=TIMEOUT)
        return resp.status, resp.read().decode("utf-8", "replace"), resp.headers
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace"), e.headers
    except Exception as e:  # noqa: BLE001 - 取证工具，任何异常都要打出来
        return -1, f"{type(e).__name__}: {e}", {}


def _get(path: str):
    try:
        with urllib.request.urlopen(f"{BASE}{path}", timeout=TIMEOUT) as r:
            return r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
    except Exception as e:  # noqa: BLE001
        return -1, f"{type(e).__name__}: {e}"


def probe_models():
    """A. GET /v1/models —— 现状：未实现（404），客户端据 `cland-llm-text` §8 不要用它探活。"""
    status, body = _get("/models")
    print(f"[A] GET /v1/models -> {status}  {body[:120]}")
    return {"probe": "/v1/models", "status": status}


def probe_invalid_model():
    """B. 非法 model id —— 用例假设 404/报错；实际?（#765 红 1）"""
    status, body, _ = _post(
        "/chat/completions",
        {"model": "no-such-model-xyz", "messages": [{"role": "user", "content": "hi"}], "max_tokens": 5},
    )
    served = ""
    try:
        served = (json.loads(body).get("model") or "")[:120]
    except Exception:  # noqa: BLE001
        pass
    print(f"[B] 非法 model -> HTTP {status} · 响应里的 model={served!r}")
    return {"probe": "invalid_model", "status": status, "served_model": served}


def probe_stream_chunks():
    """C. SSE 首帧字段 —— 用例断言 chunk 里有 `id`（OpenAI 规范有）；实际?（#765 红 2）"""
    req = urllib.request.Request(
        f"{BASE}/chat/completions",
        data=json.dumps(
            {
                "model": MODEL,
                "stream": True,
                "messages": [{"role": "user", "content": "count 1 to 3"}],
                "max_tokens": 8,
            }
        ).encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    frames = []
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
            for raw in r:
                line = raw.decode("utf-8", "replace").strip()
                if line.startswith("data:"):
                    payload = line[5:].strip()
                    if payload and payload != "[DONE]":
                        frames.append(payload)
                if len(frames) >= 2:
                    break
    except Exception as e:  # noqa: BLE001
        print(f"[C] 流式请求失败：{type(e).__name__}: {e}")
        return {"probe": "stream", "error": str(e)}
    keys = []
    for f in frames:
        try:
            keys.append(sorted(json.loads(f).keys()))
        except Exception:  # noqa: BLE001
            keys.append(["<unparsable>"])
    print(f"[C] SSE 首帧 keys = {keys}")
    print(f"    原始帧: {frames[:2]}")
    has_id = any("id" in k for k in keys)
    print(f"    chunk 含 `id`（OpenAI chunk 规范要求）= {has_id}")
    return {"probe": "stream", "chunk_keys": keys, "has_id": has_id}


def main() -> int:
    print(f"Gemma4 契约探针 · BASE={BASE} · MODEL={MODEL}")
    print("-" * 66)
    results = [probe_models(), probe_invalid_model(), probe_stream_chunks()]
    print("-" * 66)
    # 结论按**实测**生成，不写死（服务侧可能随时补契约；写死等于造滞后真源）
    invalid = next(r for r in results if r["probe"] == "invalid_model")
    stream = next(r for r in results if r["probe"] == "stream")
    print("判据（契约真源：`cland-llm-text` SKILL §8「HERE BE NOT · 单模型垫片」/ #766）：")
    ok_model = invalid.get("status") in (400, 404)
    print(f"  · 未知 model ⇒ 4xx：{invalid.get('status')} "
          f"{'✅ 符合契约（404 model_not_found）' if ok_model else '❌ 不符合：服务静默忽略 model（单模型垫片旧行为）'}")
    ok_stream = bool(stream.get("has_id"))
    print(f"  · SSE chunk 带 id/object/created：{'✅' if ok_stream else '❌ 缺字段（旧行为）'} "
          f"keys={stream.get('chunk_keys')}")
    print("  · 客户端侧强校验独立存在：`alice run --model <未知>` ⇒ No supplier found"
          "（e2e 负控 test_model_flag_reaches_model_resolution）")
    out = os.environ.get("GEMMA4_PROBE_JSON")
    if out:
        with open(out, "w", encoding="utf-8") as fh:
            json.dump(results, fh, ensure_ascii=False, indent=2)
        print(f"  · 结果已写 {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
