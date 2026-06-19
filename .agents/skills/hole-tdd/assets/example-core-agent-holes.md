# Example: alice-core-agent Hole Tests

Reference implementation for understanding the hole test pattern.

## 3-File Structure

```
docs/alice-agent-command/e2e/case-core-agent.md    ← spec
docs/alice-core-agent/e2e/scene-executor-endpoints.md  ← scene map
docs/alice-core-agent/e2e/hole_test_core_agent.py      ← impl
```

## Case Doc (abbreviated)

```markdown
### AGT-P01: `AgentExecutor.execute(Input)` happy path

| Field | Value |
|-------|-------|
| **Target** | `AgentExecutor.execute(Input)` |
| **Input** | Mock `Input` with simple goal `"say hello"` |
| **Expected** | Returns `StepResult`, instance of `Finish` or `Failure` |
| **Assertion** | `result instanceof StepResult` |
```

## Implementation (abbreviated)

```python
def test_agt_p01_executor_execute(self):
    result = run_gradle_task(":alice-core-agent:test",
                              "--tests", "*AgentPpaoLoopSpec*")
    self.assertEqual(result.returncode, 0)
```

## Key Takeaways

1. **No new Java code needed** — holes delegate to existing unit tests
2. **Each hole is one Gradle invocation** — fast, isolated, parallelizable
3. **Module boundary only** — never test internals, only input/output
4. **3-5 holes per module** — enough to verify the module is alive
