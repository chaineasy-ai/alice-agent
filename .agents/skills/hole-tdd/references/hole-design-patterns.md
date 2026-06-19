# Hole Design Patterns — Quick Reference

## Pattern 1: CRUD Round-Trip

Best for: registries, vaults, stores, context managers.

```
Create(entry) ──► Lookup(id) ──► entry
    ●                    ●
```

**Assertion**: `lookup(id) == entry` or `lookup(id) != null`

## Pattern 2: Request-Response

Best for: executors, engines, providers, validators.

```
Input ──► process() ──► Output
  ●                        ●
```

**Assertion**: `result != null`, type check only (no value inspection)

## Pattern 3: Lifecycle State Machine

Best for: context sessions, call states, connection lifecycles.

```
State1 ──► action() ──► State2 ──► action() ──► State3
  ●                                          ●
```

**Assertion**: Before state != After state, terminal state reachable

## Pattern 4: Save-Rollback

Best for: snapshots, WAL, transactional operations.

```
save(state) ──► mutate() ──► rollback() ──► state restored
    ●                                    ●
```

**Assertion**: After rollback, current state == saved state

## Pattern 5: Router/Mux

Best for: facade selectors, provider dispatch, strategy selection.

```
Input condition A ──► route() ──► Result A
Input condition B ──► route() ──► Result B
     ●                          ●
```

**Assertion**: `route(A) instanceof ExpectedTypeA`, `route(B) is not route(A)`

## Hole Template (Copy & Paste)

```markdown
### HOLE-ID: Target API

| Field | Value |
|-------|-------|
| **Target** | `ClassName.methodName()` |
| **Input** | What you pass in |
| **Expected** | What should come out |
| **Unit ref** | `ExistingSpecName.groovy` (if backed by unit test) |
```
