---
name: hole-test
description: |
  Hole Test workflow — module-level endpoint probing for Java/Gradle modular monoliths.
  Hole tests (洞测试) are neither E2E nor unit; they are minimal "probes" drilled into
  a module's public API boundary to verify data flows in and out correctly.
  Use when adding module-level coverage between E2E (10%) and unit tests (70%),
  forming the 20% module layer in the test pyramid.
compatibility:
  - "adding module-level endpoint coverage for Java modules"
  - "creating hole tests for core services (planner, guardrail, memory, model, etc.)"
  - "verifying public API boundaries without testing internal logic"
  - "generating case doc → scene doc → hole_test.py for each module"
  - "documenting probe points (holes) with input/output assertions"
  - "identifying missing module coverage in the test pyramid"
dir: E:\work\chaineasy-ai\alice-agent\.agents\skills\hole-test
nav:
  scripts: "scripts/ — hole test templates and runner helpers"
  references: "references/ — probe pattern cheatsheet, hole design principles"
  assets: "assets/ — example hole tests for reference"
---

# hole-test — Module-Level Hole Test Skill

A workflow for creating **hole tests** — minimal, low-cost endpoint probes that sit
between E2E and unit tests in the pyramid. Each hole is a single assertion at a
module's public API boundary.

## When to Use Hole Tests

| When | Don't use hole tests |
|------|---------------------|
| Module has 0 or low unit test coverage | When you need to test internal algorithms |
| You need fast feedback on module boundaries | When full E2E would be overkill |
| A module has public API but no integration test | When unit tests already cover the boundary well |
| You're adding a new module and want a baseline probe | When the test requires mocking complex internals |

## The 3-File Pattern

For each module, create exactly 3 files:

```
docs/
├── alice-agent-command/e2e/
│   └── case-<module>.md              ← 1. Case doc: hole spec
└── <module>/e2e/
    ├── scene-<module>-endpoints.md   ← 2. Scene doc: probe map
    └── hole_test_<module>.py         ← 3. Python implementation
```

### 1. Case Doc — `docs/alice-agent-command/e2e/case-<module>.md`

Defines each hole:
```markdown
### HOLE-ID: Target API

| Field | Value |
|-------|-------|
| **Target** | `ClassName.methodName()` |
| **Input** | What you pass in |
| **Expected** | What comes out |
| **Assertion** | Single assert statement |
```

### 2. Scene Doc — `docs/<module>/e2e/scene-<module>-endpoints.md`

ASCII probe map + how to run:
```markdown
## Probe Map
```
Input ──► API.method() ──► Output
             ● (HOLE-ID)
```

## How to Run
```bash
python docs/<module>/e2e/hole_test_<module>.py
```
```

### 3. Implementation — `docs/<module>/e2e/hole_test_<module>.py`

```python
#!/usr/bin/env python3
"""
Hole Test — <module> module endpoints.
"""
import unittest
from helpers import run_gradle_task, PROJECT_ROOT

class Test<Module>Holes(unittest.TestCase):
    """Hole tests for <module>."""

    @classmethod
    def setUpClass(cls):
        cls.build_ok = (PROJECT_ROOT / "<module>" / "build").is_dir()

    def test_hole_id(self):
        """HOLE-ID: description."""
        if not self.build_ok:
            self.skipTest("Module not built.")
        result = run_gradle_task(":<module>:test",
                                  "--tests", "*TargetSpec*")
        self.assertEqual(result.returncode, 0)
```

## Hole Design Principles

### 1. One Hole, One Assertion

Each hole verifies exactly one boundary. No more.

```
✅ Good:  AgentExecutor.execute(Input) → !null  (1 assert)
❌ Bad:   AgentExecutor.execute(Input) → !null, type check, side effect check (3 asserts)
```

### 2. Probe the Boundary, Not the Internals

```ascii
         ┌────────────────────┐
         │      Module        │
         │                    │
  Input ──►  ●          ●  ──► Output
         │   (probe 1)  (probe 2) │
         │                    │
         │    ●          ●    │
         │  (probe 3)  (probe 4)│
         └────────────────────┘
```

The holes are on the **edge** of the module — you don't look inside.

### 3. 3-5 Holes Per Module

| Module size | Recommended holes |
|-------------|-------------------|
| Small (3-5 src files) | 3 holes |
| Medium (6-15 src files) | 4 holes |
| Large (16+ src files) | 5 holes |

### 4. Hole Naming Convention

Format: `{MODULE_PREFIX}-P{NN}`

| Prefix | Module |
|--------|--------|
| AGT | alice-core-agent |
| PLN | alice-core-planner |
| ENV | alice-env-adapter |
| TGW | alice-tool-gateway |
| MEM | alice-memory-vault |
| MDL | alice-model |
| GRD | alice-guardrail |
| WEB | alice-facade-web |
| BTS | alice-bootstrap |

### 5. When a Hole Passes Via Existing Unit Tests

If the hole can be verified by running an existing Spec test:

```python
result = run_gradle_task(":<module>:test", "--tests", "*ExistingSpecName*")
self.assertEqual(result.returncode, 0)
```

If there are no unit tests yet (e.g. `alice-guardrail`):

```python
result = run_gradle_task(":<module>:test")
if result.returncode != 0 and "No tests executed" in result.stderr:
    self.skipTest("No unit tests exist yet — hole is open (uncovered)")
```

## Reference: Full Module Probe Map

| Module | Holes | Probe IDs |
|--------|-------|-----------|
| alice-core-agent | 5 | AGT-P01~P05 |
| alice-core-planner | 4 | PLN-P01~P04 |
| alice-env-adapter | 4 | ENV-P01~P04 |
| alice-tool-gateway | 4 | TGW-P01~P04 |
| alice-memory-vault | 5 | MEM-P01~P05 |
| alice-model | 5 | MDL-P01~P05 |
| alice-guardrail | 5 | GRD-P01~P05 |
| alice-facade-web | 3 | WEB-P01~P03 |
| alice-bootstrap | 3 | BTS-P01~P03 |
