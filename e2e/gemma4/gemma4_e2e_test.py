#!/usr/bin/env python3
"""
E2E test for Gemma4 model via OpenAI-compatible API at http://192.168.1.14:10303/v1

Usage:
    # Run all tests
    python gemma4_e2e_test.py

    # Run a specific test
    python gemma4_e2e_test.py TestGemma4E2E.test_basic_chat

    # With verbose output
    python gemma4_e2e_test.py -v
"""

import json
import os
import sys
import time
import unittest

import requests

# ── Configuration ──────────────────────────────────────────────────────────

BASE_URL = os.environ.get("GEMMA4_BASE_URL", "http://192.168.1.14:10303/v1")
API_KEY = os.environ.get("GEMMA4_API_KEY", "")
MODEL = os.environ.get("GEMMA4_MODEL", "gemma-4")
TIMEOUT = int(os.environ.get("GEMMA4_TIMEOUT", "120"))

# ── Reusable client helper ─────────────────────────────────────────────────


class OpenAICompatibleClient:
    """Lightweight OpenAI-compatible chat completions client."""

    def __init__(self, base_url: str, api_key: str = ""):
        self.base_url = base_url.rstrip("/")
        self.session = requests.Session()
        if api_key:
            self.session.headers.update({"Authorization": f"Bearer {api_key}"})
        self.session.headers.update({"Content-Type": "application/json"})

    def chat_completions(
        self,
        model: str,
        messages: list,
        stream: bool = False,
        temperature: float = 0.7,
        max_tokens: int | None = None,
        timeout: int = 120,
    ) -> dict:
        """Send a chat completion request (non-streaming)."""
        payload: dict = {
            "model": model,
            "messages": messages,
            "stream": stream,
            "temperature": temperature,
        }
        if max_tokens is not None:
            payload["max_tokens"] = max_tokens

        url = f"{self.base_url}/chat/completions"
        resp = self.session.post(url, json=payload, timeout=timeout)
        resp.raise_for_status()
        return resp.json()

    def chat_completions_stream(
        self,
        model: str,
        messages: list,
        temperature: float = 0.7,
        max_tokens: int | None = None,
        timeout: int = 120,
    ):
        """Send a streaming chat completion request (yields SSE chunks)."""
        payload: dict = {
            "model": model,
            "messages": messages,
            "stream": True,
            "temperature": temperature,
        }
        if max_tokens is not None:
            payload["max_tokens"] = max_tokens

        url = f"{self.base_url}/chat/completions"
        with self.session.post(url, json=payload, stream=True, timeout=timeout) as resp:
            resp.raise_for_status()
            for line in resp.iter_lines(decode_unicode=True):
                if line:
                    line = line.strip()
                    if line == "data: [DONE]":
                        break
                    if line.startswith("data: "):
                        yield json.loads(line[6:])


# ── Test Suite ─────────────────────────────────────────────────────────────


class TestGemma4E2E(unittest.TestCase):
    """End-to-end tests for the Gemma4 model API."""

    @classmethod
    def setUpClass(cls):
        cls.client = OpenAICompatibleClient(BASE_URL, API_KEY)
        # Quick health check
        cls._check_service()

    @classmethod
    def _check_service(cls):
        """Verify the API is reachable before running tests."""
        try:
            resp = requests.get(f"{BASE_URL.rstrip('/v1')}/health", timeout=5)
            if resp.status_code < 500:
                return
        except requests.RequestException:
            pass
        # Fallback: try a minimal chat to verify
        try:
            cls.client.chat_completions(
                model=MODEL,
                messages=[{"role": "user", "content": "ping"}],
                max_tokens=5,
                timeout=10,
            )
        except requests.RequestException as e:
            raise unittest.SkipTest(
                f"Gemma4 API at {BASE_URL} is not reachable: {e}\n"
                "Skipping E2E tests. Start the service first."
            ) from e

    # ── Basic Tests ────────────────────────────────────────────────────────

    def test_basic_chat(self):
        """Send a simple prompt and verify a non-empty response."""
        resp = self.client.chat_completions(
            model=MODEL,
            messages=[{"role": "user", "content": "Say 'hello' in one word."}],
            max_tokens=20,
            timeout=TIMEOUT,
        )
        self._assert_valid_response(resp)
        content = resp["choices"][0]["message"]["content"]
        self.assertTrue(content.strip(), "Response content should not be empty")
        print(f"\n  ↳ Response: {content.strip()[:120]}")

    def test_multi_turn_conversation(self):
        """Send multiple messages and verify context is maintained."""
        messages = [
            {"role": "user", "content": "My favorite color is blue."},
            {"role": "assistant", "content": "Got it! Blue is a great color."},
            {"role": "user", "content": "What is my favorite color?"},
        ]
        resp = self.client.chat_completions(
            model=MODEL, messages=messages, max_tokens=50, timeout=TIMEOUT
        )
        self._assert_valid_response(resp)
        content = resp["choices"][0]["message"]["content"].strip().lower()
        self.assertIn(
            "blue",
            content,
            f"Expected 'blue' in response to context question, got: {content[:200]}",
        )
        print(f"\n  ↳ Multi-turn context preserved: {content[:120]}")

    def test_japanese_prompt(self):
        """Verify non-English (Japanese) prompt works correctly."""
        resp = self.client.chat_completions(
            model=MODEL,
            messages=[{"role": "user", "content": "こんにちは、あなたの名前は何ですか？"}],
            max_tokens=50,
            timeout=TIMEOUT,
        )
        self._assert_valid_response(resp)
        content = resp["choices"][0]["message"]["content"]
        self.assertTrue(content.strip(), "Japanese response should not be empty")
        print(f"\n  ↳ Japansese response: {content.strip()[:120]}")

    # ── Streaming Tests ────────────────────────────────────────────────────

    def test_streaming_chat(self):
        """流式每帧必须是规范 chunk（#766 契约）：有 id / object=chat.completion.chunk / 同一 id 恒定。

        改前：垫片只发 `{"choices":…}`（无 id/object/created），用例 `assertIn("id")` 红。
        改后：每帧带 `id`/`object`/`created`/`model`，且**同一请求所有帧共享一个 id**
        （工具调用帧、stop 帧、usage 尾包全覆盖）。
        """
        chunks = []
        for chunk in self.client.chat_completions_stream(
            model=MODEL,
            messages=[{"role": "user", "content": "Count from 1 to 5."}],
            max_tokens=50,
            timeout=TIMEOUT,
        ):
            chunks.append(chunk)

        self.assertGreater(len(chunks), 0, "Should receive at least one streaming chunk")

        # 每个 delta 帧的规范形状
        first = chunks[0]
        self.assertIn("choices", first)
        self.assertIn("id", first, "SSE chunk 必须带 id（OpenAI chat.completion.chunk）")
        self.assertEqual(first.get("object"), "chat.completion.chunk",
                         f"object 必须是 chat.completion.chunk，实际 {first.get('object')!r}")
        self.assertIn("created", first)
        self.assertIn("model", first)

        # 同一请求的所有帧共享一个 id（帧间 id 漂移 = 客户端无法拼接同一次补全）
        ids = {c.get("id") for c in chunks if c.get("id")}
        self.assertEqual(len(ids), 1, f"同一请求的所有帧必须共享一个 id，实际看到 {ids}")

        # Extract full content
        full_content = ""
        for chunk in chunks:
            choices = chunk.get("choices", [])
            if choices and "delta" in choices[0]:
                delta = choices[0]["delta"]
                if "content" in delta:
                    full_content += delta["content"]

        self.assertTrue(full_content.strip(), "Streaming content should not be empty")
        print(f"\n  ↳ Streamed content ({len(chunks)} chunks): {full_content.strip()[:120]}")

    def test_streaming_finish_reason(self):
        """Verify the last streaming chunk has a finish_reason."""
        last_chunk = None
        for chunk in self.client.chat_completions_stream(
            model=MODEL,
            messages=[{"role": "user", "content": "Say 'done'."}],
            max_tokens=10,
            timeout=TIMEOUT,
        ):
            last_chunk = chunk

        if last_chunk:
            choices = last_chunk.get("choices", [])
            if choices:
                finish_reason = choices[0].get("finish_reason")
                self.assertIn(
                    finish_reason,
                    ["stop", "length"],
                    f"Expected finish_reason 'stop' or 'length', got '{finish_reason}'",
                )
                print(f"\n  ↳ Finish reason: {finish_reason}")

    # ── System Prompt Tests ────────────────────────────────────────────────

    def test_system_prompt(self):
        """Verify system prompt influences the model behavior."""
        resp = self.client.chat_completions(
            model=MODEL,
            messages=[
                {
                    "role": "system",
                    "content": "You are a helpful assistant that always responds in Japanese.",
                },
                {"role": "user", "content": "Say 'hello'."},
            ],
            max_tokens=50,
            timeout=TIMEOUT,
        )
        self._assert_valid_response(resp)
        content = resp["choices"][0]["message"]["content"]
        self.assertTrue(content.strip(), "System-prompt-guided response should not be empty")
        print(f"\n  ↳ System-prompt response: {content.strip()[:120]}")

    # ── Token Usage Tests ──────────────────────────────────────────────────

    def test_token_usage_reported(self):
        """Verify the API reports token usage in the response."""
        resp = self.client.chat_completions(
            model=MODEL,
            messages=[{"role": "user", "content": "What is 2+2?"}],
            max_tokens=30,
            timeout=TIMEOUT,
        )
        usage = resp.get("usage")
        if usage:
            self.assertIn("prompt_tokens", usage)
            self.assertIn("completion_tokens", usage)
            self.assertIn("total_tokens", usage)
            self.assertGreater(usage["prompt_tokens"], 0, "prompt_tokens should be > 0")
            self.assertGreater(usage["completion_tokens"], 0, "completion_tokens should be > 0")
            print(
                f"\n  ↳ Token usage: {usage['prompt_tokens']} in + "
                f"{usage['completion_tokens']} out = {usage['total_tokens']} total"
            )
        else:
            print("\n  ↳ Token usage not reported (optional field)")

    # ── Error Handling Tests ───────────────────────────────────────────────

    def test_empty_messages_returns_error(self):
        """Verify that empty messages list results in a server error."""
        with self.assertRaises(requests.exceptions.HTTPError) as ctx:
            self.client.chat_completions(
                model=MODEL,
                messages=[],
                max_tokens=10,
                timeout=TIMEOUT,
            )
        status = ctx.exception.response.status_code
        self.assertGreaterEqual(status, 400, "Empty messages should trigger an HTTP error")
        print(f"\n  ↳ Empty messages correctly rejected with HTTP {status}")
        # The Gemma4 server returns 500 for empty messages (server-side index error)
        detail = ctx.exception.response.text[:200]
        print(f"  ↳ Error detail: {detail}")

    def test_invalid_model_returns_error(self):
        """未知 model ⇒ HTTP 404 + code=model_not_found（契约：#766 · cland-llm f5d38e7）。

        改前：单模型垫片**静默忽略** `model`，任何 id 都 200 ⇒ 用例只能退化成"有错就行"，
        甚至把超时/连接错误也当通过 —— 那不是宽容，是**假容忍**（服务坏了也算绿）。
        改后契约（`cland-llm-text` SKILL §8「HERE BE NOT · 单模型垫片」）：
          · 别名表命中（gemma-4 / gemma4 / gemma-4-26b-a4b / gguf 名）⇒ 放行
          · 未知 ⇒ **404**，错误体 `code=model_not_found`、`param=model`
        超时/连接错误**不算通过**（说明服务坏，不是契约满足）。
        """
        import requests.exceptions as req_exc

        with self.assertRaises(req_exc.HTTPError) as ctx:
            self.client.chat_completions(
                model="non-existent-model-xyz",
                messages=[{"role": "user", "content": "hi"}],
                max_tokens=10,
                timeout=20,
            )
        resp = ctx.exception.response
        self.assertEqual(resp.status_code, 404, f"未知 model 必须 404，实际 {resp.status_code}: {resp.text[:200]}")
        try:
            body = resp.json()
        except Exception:  # noqa: BLE001 - 兜底：至少状态码对
            body = {}
        self.assertEqual(body.get("error", {}).get("code"), "model_not_found",
                         f"错误体应带 code=model_not_found: {resp.text[:200]}")
        self.assertIn("gemma", resp.text.lower(), "错误体应说明服务只服务哪一个模型")
        print(f"\n  ↳ Invalid model correctly rejected: HTTP 404 model_not_found")

    def test_model_aliases_accepted(self):
        """别名表命中即放行（#766 契约的另一半）：legacy id / 裸名都必须 200。

        这条是上一条的**正控** —— 否则"404"可以靠"什么都拒"来满足。
        """
        for alias in ("gemma-4", "gemma4", "gemma-4-26b-a4b"):
            with self.subTest(alias=alias):
                resp = self.client.chat_completions(
                    model=alias,
                    messages=[{"role": "user", "content": "Say OK in one word."}],
                    max_tokens=5,
                    timeout=60,
                )
                self.assertIn("choices", resp)
                # 非流式响应回写**规范 id**（改前是 gguf 绝对路径，客户端无法据此判断）
                served = resp.get("model", "")
                self.assertFalse(served.endswith(".gguf"), f"model 应回写规范 id 而非 gguf 路径：{served}")
                print(f"\n  ↳ alias {alias!r} accepted, served model={served!r}")

    # ── Performance Tests ──────────────────────────────────────────────────

    def test_response_latency_under_threshold(self):
        """Verify the API responds within a reasonable time (60s for Gemma4 local)."""
        start = time.time()
        self.client.chat_completions(
            model=MODEL,
            messages=[{"role": "user", "content": "Say 'fast' in one word."}],
            max_tokens=10,
            timeout=TIMEOUT,
        )
        elapsed = time.time() - start
        threshold = 60.0
        self.assertLessEqual(
            elapsed,
            threshold,
            f"Response took {elapsed:.2f}s, expected under {threshold}s",
        )
        print(f"\n  ↳ Response latency: {elapsed:.2f}s")

    # ── Helpers ────────────────────────────────────────────────────────────

    def _assert_valid_response(self, resp: dict):
        """Assert common response structure."""
        self.assertIn("id", resp, "Response missing 'id'")
        self.assertIn("object", resp, "Response missing 'object'")
        self.assertIn("created", resp, "Response missing 'created'")
        self.assertIn("model", resp, "Response missing 'model'")
        self.assertIn("choices", resp, "Response missing 'choices'")
        self.assertGreater(len(resp["choices"]), 0, "No choices returned")
        choice = resp["choices"][0]
        self.assertIn("message", choice, "Choice missing 'message'")
        self.assertIn("role", choice["message"], "Message missing 'role'")
        self.assertEqual(choice["message"]["role"], "assistant")
        self.assertIn("finish_reason", choice, "Choice missing 'finish_reason'")


# ── Main ───────────────────────────────────────────────────────────────────

if __name__ == "__main__":
    print("=" * 64)
    print(f"  Gemma4 E2E Test Suite")
    print(f"  API:   {BASE_URL}")
    print(f"  Model: {MODEL}")
    print(f"  Timeout: {TIMEOUT}s")
    print("=" * 64)
    unittest.main(verbosity=2)
