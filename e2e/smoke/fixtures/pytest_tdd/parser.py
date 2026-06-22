"""JSON payload parser."""

import json


def parse_payload(raw: str) -> dict:
    """Parse a JSON payload string into a dict."""
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        return {"error": "invalid"}
