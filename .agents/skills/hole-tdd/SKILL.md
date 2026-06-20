---
name: hole-tdd
description: |
  Hole Test-Driven Development — TDD workflow for module-level endpoint probing.
  Hole tests (洞测试) follow Red → Green → Refactor, are neither E2E nor unit;
  they are minimal "probes" drilled into a module's public API boundary to verify
  data flows in and out correctly.
  Use when adding module-level coverage between E2E (10%) and unit tests (70%),
  forming the 20% module layer in the test pyramid.
compatibility:
  - "adding module-level endpoint coverage for Java modules following TDD"
  - "creating hole tests for core services (planner, guardrail, memory, model, etc.)"
  - "verifying public API boundaries without testing internal logic"
  - "generating case doc → scene doc → hole_test.py for each module"
  - "documenting probe points (holes) with input/output assertions"
  - "identifying missing module coverage in the test pyramid"
dir: E:\work\chaineasy-ai\alice-agent\.agents\skills\hole-tdd
nav:
  scripts: "scripts/ — hole test scaffolding generator and runner helpers"
  references: "references/ — probe pattern cheatsheet, hole design principles"
  assets: "assets/ — example hole tests for reference"
---

# hole-tdd — Hole Test-Driven Development

A **TDD-first** workflow for creating **hole tests** — minimal, low-cost endpoint probes
that sit between E2E and unit tests in the pyramid. Each hole follows the
Red → Green → Refactor cycle, with a single assertion at a module's public API boundary.

## The TDD Cycle

```ascii
  ┌─────────────────────────────────────────────────┐
  │                                                 │
  │  1. Define the hole (case doc)                  │
  │     → Write what you'll probe, input, expected  │
  │                                                 │
  │  2. Write the failing test (Red)                │
  │     → hole_test.py with assert(False)           │
  │     → Run: python hole_test_<module>.py → FAIL  │
  │                                                 │
  │  3. Make it pass (Green)                        │
  │     → Fill in the real assertion                │
  │     → Run: python hole_test_<module>.py → PASS  │
  │                                                 │
  │  4. Refactor (if needed)                        │
  │     → Clean up test code, keep 1 assert/hole    │
  │                                                 │
  └─────────────────────────────────────────────────┘
```

## When to Use Hole-TDD

| When | Don't use |
|------|-----------|
| Module has 0 or low unit test coverage | When you need to test internal algorithms |
| You need fast feedback on module boundaries | When full E2E would be overkill |
| A module has public API but no integration test | When unit tests already cover the boundary well |
| You're adding a new module and want a baseline probe | When the test requires mocking complex internals |

## The 3-File Pattern

For each module, create exactly 3 files (TDD order: case doc → test → scene doc):

```ascii
Step 1: Write case spec
  ↓
docs/alice-agent-command/e2e/case-<module>.md    ← What & Why
  ↓
Step 2: Write failing test (RED)
  ↓
docs/<module>/e2e/hole_test_<module>.py           ← How (initially asserts False)
  ↓
Step 3: Run & verify RED → make GREEN
  ↓
Step 4: Write scene doc (documentation)
  ↓
docs/<module>/e2e/scene-<module>-endpoints.md    ← Probe map & run guide
```

### 1. Case Doc — `docs/alice-agent-command/e2e/case-<module>.md`

Defines each hole with clear spec (the **RED** target):

```markdown
### HOLE-ID: Target API

| Field | Value |
|-------|-------|
| **Target** | `ClassName.methodName()` |
| **Input** | What you pass in |
| **Expected** | What should come out |
| **Assertion** | Single assert statement |
```

### 2. Implementation (start RED) — `docs/<module>/e2e/hole_test_<module>.py`

Start with a failing test:

```python
def test_hole_id(self):
    """HOLE-ID: description."""
    self.assertTrue(False, "HOLE-ID: RED — implement assertion")
```

Then replace with real assertion:

```python
def test_hole_id(self):
    """HOLE-ID: description."""
    if not self.build_ok:
        self.skipTest("Module not built.")
    result = run_gradle_task(":<module>:test",
                              "--tests", "*TargetSpec*")
    self.assertEqual(result.returncode, 0)
```

### 3. Scene Doc — `docs/<module>/e2e/scene-<module>-endpoints.md`

ASCII probe map + how to run (written **GREEN** after tests pass):

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

## Hole Design Principles

### 1. RED First, Always

Before writing any implementation, write the assertion that will fail:

```
✅ RED:  assertTrue(False, "AGT-P01: not implemented yet")
✅ RED:  assertEqual(run_gradle_task().returncode, 0)
         # This fails because no unit test exists yet
❌ NO:   Write the passing test first and run once to confirm
```

### 2. One Hole, One Assertion

Each hole verifies exactly one boundary. No more.

```
✅ Good:  AgentExecutor.execute(Input) → !null  (1 assert)
❌ Bad:   AgentExecutor.execute(Input) → !null, type check, side effect check (3 asserts)
```

### 3. Probe the Boundary, Not the Internals

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

### 4. 3-5 Holes Per Module

| Module size | Recommended holes |
|-------------|-------------------|
| Small (3-5 src files) | 3 holes |
| Medium (6-15 src files) | 4 holes |
| Large (16+ src files) | 5 holes |

### 5. Hole Naming Convention

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

### 6. Green Through Existing Unit Tests

If the hole can be verified by running an existing Spec:

```python
result = run_gradle_task(":<module>:test", "--tests", "*ExistingSpecName*")
self.assertEqual(result.returncode, 0)  # GREEN if unit tests pass
```

If no unit tests exist yet:

```python
result = run_gradle_task(":<module>:test")
if result.returncode != 0 and "No tests executed" in result.stderr:
    self.skipTest("No unit tests yet — hole is open (uncovered)")
    # This is still RED — the hole documents a coverage gap
```

### 7. Green Through Direct Module Entry (runHoleTest)

If the hole requires a **real module boundary call** (not just running unit tests),
create a dedicated Java main class in a separate source set (e.g. `src/hole/java/`)
and invoke it via Gradle JavaExec:

**Step 1**: Define the source set in `build.gradle`:

```groovy
sourceSets {
    hole {
        java { srcDirs = ['src/hole/java'] }
        compileClasspath += sourceSets.main.output
        runtimeClasspath += sourceSets.main.runtimeClasspath
    }
}
tasks.register('runHoleTest', JavaExec) {
    classpath = sourceSets.hole.runtimeClasspath
    mainClass = 'org.example.module.MyHoleTest'
    modularity.inferModulePath = true
}
```

**Step 2**: Write the Java hole test entry:

```java
public class MyHoleTest {
    public static void main(String[] args) {
        switch (args[0]) {
            case "lookup" -> testLookup();
            case "list"   -> testList();
        }
    }
    static void fail(String msg) { System.err.println("FAIL: " + msg); System.exit(1); }
}
```

**Step 3**: In Python hole test, call via Gradle JavaExec:

```python
def run_hole(key: str, *extra_args, timeout=60):
    args_list = [key] + list(extra_args)
    quoted = ["'{}'".format(a) if ' ' in a else a for a in args_list]
    return run_gradle_task(":<module>:runHoleTest",
                           "--args", " ".join(quoted), timeout=timeout)

def test_hole_id(self):
    result = run_hole("lookup")
    self.assertEqual(result.returncode, 0)
    self.assertIn("PASS:", result.stdout)
```

Use this pattern when you need to:
- Test a tool that requires real network access (e.g. `web_search` → DuckDuckGo)
- Exercise the full `ToolDiscovery → ToolRegistry → ExecutionEngine` chain
- Avoid depending on unit test runners for module boundary verification

## RED Status Convention

| Status | Meaning |
|--------|---------|
| 🟥 RED | Hole defined, test written, assertion fails (or skipped with gap noted) |
| 🟩 GREEN | Hole test passes — module boundary verified |
| ⏭️ SKIP | Hole cannot be tested via this path (e.g. JLine terminal) |

In the scene doc, mark each hole's status:

```markdown
| Hole | Status | Notes |
|------|--------|-------|
| AGT-P01 | 🟩 GREEN | via AgentPpaoLoopSpec |
| AGT-P02 | 🟩 GREEN | via StepResultSpec |
| AGT-P05 | 🟥 RED | No SubAgentManager test yet |
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
