"""Tests for parser module."""

import pytest
from parser import parse_payload


def test_payload_parsing():
    """Parse a valid JSON payload."""
    result = parse_payload('{"key": "value", "num": 42}')
    assert result["key"] == "value"
    assert result["num"] == 42


def test_payload_parsing_broken():
    """Parse an invalid JSON payload — this test is BROKEN on purpose."""
    # BUG: parse_payload should handle malformed JSON gracefully
    # Expected: returns {"error": "invalid"} instead of crashing
    result = parse_payload("not valid json")
    assert result == {"error": "invalid"}
