"""
PMTEV Smoke Test Configuration.

Defines shared config, fixture management, and repo paths for
the 3 standard smoke test cases.

See also:
    docs/Agent 冒烟测试用例规范文档.md — PMTEV specification
    e2e/smoke/cases.py — the 3 case definitions
    e2e/smoke/runner.py — Python driver for batch execution
"""

import os
import tempfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional


# ── Fixture Repository Paths ───────────────────────────────────────────────

# Root of the Alice Agent project (used as workspace root for smoke tests)
PROJECT_ROOT = Path(__file__).resolve().parents[2]

# Where fixture repos are stored (created on-demand by runner)
FIXTURES_DIR = PROJECT_ROOT / "e2e" / "smoke" / "fixtures"

# Temporary workspace for each smoke case execution
WORKSPACE_DIR = Path(tempfile.gettempdir()) / "alice-smoke"


# ── Smoke Case Definition ─────────────────────────────────────────────────


@dataclass
class SmokeCase:
    """A single PMTEV smoke test case.

    Attributes:
        instance_id:   Unique case ID (e.g. "smoke__case-1")
        repo_path:     Path to the fixture repository
        problem_description:  User input to the Agent (per 冒烟测试规范文档)
        target_model:  Model to use (optional, defaults to PROJECT_MODEL)
        assertions:    List of passing criteria
    """
    instance_id: str
    repo_path: Path
    problem_description: str
    target_model: Optional[str] = None
    assertions: list[str] = field(default_factory=list)
    timeout_seconds: int = 300


# ── Model Configuration ───────────────────────────────────────────────────

# Default model for smoke tests. Set ALICE_SMOKE_MODEL to override.
# Requires corresponding DEEPSEEK_API_KEY env var to be set.
PROJECT_MODEL = os.environ.get("ALICE_SMOKE_MODEL", "deepseek-chat")
DEEPSEEK_API_KEY = os.environ.get("DEEPSEEK_API_KEY", "")
