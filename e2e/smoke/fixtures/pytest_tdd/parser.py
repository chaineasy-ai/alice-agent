"""JSON payload parser."""

import json


def parse_payload(raw: str) -> dict:
    """Parse a JSON payload string into a dict."""
    return json.loads(raw)
