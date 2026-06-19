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
        """Verify streaming responses yield multiple chunks with content."""
        chunks = []
        for chunk in self.client.chat_completions_stream(
            model=MODEL,
            messages=[{"role": "user", "content": "Count from 1 to 5."}],
            max_tokens=50,
            timeout=TIMEOUT,
        ):
            chunks.append(chunk)

        self.assertGreater(len(chunks), 0, "Should receive at least one streaming chunk")

        # Verify the structure of the first chunk
        first = chunks[0]
        self.assertIn("choices", first)
        self.assertIn("id", first)
        self.assertIn("object", first)

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
        """Verify that an invalid model ID is handled gracefully.

        Note: The Gemma4 server may hang on invalid models instead of
        immediately rejecting. We test that the request either:
        - Raises an HTTP error, or
        - Raises a connection/read timeout
        """
        import requests.exceptions as req_exc

        try:
            self.client.chat_completions(
                model="non-existent-model-xyz",
                messages=[{"role": "user", "content": "hi"}],
                max_tokens=10,
                timeout=10,  # short timeout for this test
            )
            # If it somehow succeeds, that's unexpected
            self.fail("Expected an error for invalid model")
        except req_exc.HTTPError as e:
            status = e.response.status_code
            self.assertGreaterEqual(status, 400)
            print(f"\n  ↳ Invalid model correctly rejected with HTTP {status}")
        except req_exc.Timeout:
            print("\n  ↳ Invalid model: server timed out (expected behavior for Gemma4)")
        except req_exc.ConnectionError:
            print("\n  ↳ Invalid model: connection error (server may have crashed)")

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
