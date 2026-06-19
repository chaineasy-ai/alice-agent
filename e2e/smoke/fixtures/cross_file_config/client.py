"""HTTP client using application configuration."""

from config import TIMEOUT_MS, MAX_RETRIES


def make_request(url: str) -> str:
    """Make an HTTP request with configured timeout."""
    print(f"Connecting to {url} with timeout={TIMEOUT_MS}ms...")
    # Simulate request
    return f"Response from {url}"


def retry_request(url: str, attempt: int = 1) -> str:
    """Retry a request up to MAX_RETRIES."""
    if attempt > MAX_RETRIES:
        raise RuntimeError("Max retries exceeded")
    try:
        return make_request(url)
    except Exception:
        return retry_request(url, attempt + 1)
