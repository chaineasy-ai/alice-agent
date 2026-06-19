#!/usr/bin/env python3
"""
Lightweight OpenAI-compatible client for Gemma4 API.

Usage:
    from gemma_4_client import OpenAICompatibleClient

    client = OpenAICompatibleClient(
        base_url="http://192.168.1.14:10303/v1",
        api_key="",
    )

    # Non-streaming
    resp = client.chat_completions(
        model="gemma-4",
        messages=[{"role": "user", "content": "Hello"}],
    )
    print(resp["choices"][0]["message"]["content"])

    # Streaming
    for chunk in client.chat_completions_stream(
        model="gemma-4",
        messages=[{"role": "user", "content": "Hello"}],
    ):
        if chunk["choices"][0]["delta"].get("content"):
            print(chunk["choices"][0]["delta"]["content"], end="")
"""

import json
from typing import Generator, Optional

import requests


class OpenAICompatibleClient:
    """Lightweight OpenAI-compatible chat completions client.

    Works with any OpenAI-compatible API endpoint (Gemma4, Ollama, vLLM, etc.).
    """

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
        max_tokens: Optional[int] = None,
        top_p: Optional[float] = None,
        timeout: int = 120,
    ) -> dict:
        """Send a chat completion request (non-streaming).

        Args:
            model: Model ID (e.g., "gemma-4", "gpt-4o")
            messages: List of message dicts with "role" and "content"
            stream: Whether to stream (non-streaming call)
            temperature: Sampling temperature (0-2)
            max_tokens: Maximum tokens to generate
            top_p: Nucleus sampling parameter
            timeout: Request timeout in seconds

        Returns:
            Parsed JSON response dict
        """
        payload: dict = {
            "model": model,
            "messages": messages,
            "stream": stream,
            "temperature": temperature,
        }
        if max_tokens is not None:
            payload["max_tokens"] = max_tokens
        if top_p is not None:
            payload["top_p"] = top_p

        url = f"{self.base_url}/chat/completions"
        resp = self.session.post(url, json=payload, timeout=timeout)
        resp.raise_for_status()
        return resp.json()

    def chat_completions_stream(
        self,
        model: str,
        messages: list,
        temperature: float = 0.7,
        max_tokens: Optional[int] = None,
        top_p: Optional[float] = None,
        timeout: int = 120,
    ) -> Generator[dict, None, None]:
        """Send a streaming chat completion request (yields parsed SSE chunks).

        Args:
            model: Model ID
            messages: List of message dicts
            temperature: Sampling temperature
            max_tokens: Maximum tokens to generate
            top_p: Nucleus sampling parameter
            timeout: Request timeout in seconds

        Yields:
            Parsed JSON dict for each SSE chunk
        """
        payload: dict = {
            "model": model,
            "messages": messages,
            "stream": True,
            "temperature": temperature,
        }
        if max_tokens is not None:
            payload["max_tokens"] = max_tokens
        if top_p is not None:
            payload["top_p"] = top_p

        url = f"{self.base_url}/chat/completions"
        with self.session.post(url, json=payload, stream=True, timeout=timeout) as resp:
            resp.raise_for_status()
            for line in resp.iter_lines(decode_unicode=True):
                if not line:
                    continue
                line = line.strip()
                if line == "data: [DONE]":
                    break
                if line.startswith("data: "):
                    yield json.loads(line[6:])

    def __del__(self):
        """Ensure session is closed on garbage collection."""
        self.session.close()
