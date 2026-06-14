---

title: "Crystallizer Interface Contract"
summary: "Public API contract for the Crystallizer component"
read_when:
  - "implementing or consuming the Crystallizer"
scope:
  - "alice-memory-vault"
status: "active"
updated: "2026-06-15"
---

# Crystallizer Interface Contract

## Package
`org.cland.alice.memory.dreaming`

## Related Contracts
- [DreamingEngine](./DreamingEngine-contract.md)

## Crystallizer API

```java
public final class Crystallizer {
    public Crystallizer(ProceduralVault proceduralVault);

    /**
     * Analyze WAL messages for repeated tool-call sequences.
     * Sequences of 3+ identical tool-call patterns within the same
     * session are crystallized into SOP entries.
     *
     * @param messages   Raw WAL messages from the session
     * @param sessionId  Source session (for SOP provenance)
     * @return number of SOPs created
     */
    public int crystallize(List<RawMessage> messages, String sessionId);
}
```

## Behavioral Contract

1. Scan the WAL message list for `assistant` messages containing `toolCalls`
2. Extract ordered tool-call sequences (consecutive assistant + tool messages
   represent one step)
3. Build a sliding window of tool names (window size: 2–5 tools)
4. If any exact sequence appears 3+ times → create SOP:
   - sopId: "dreaming-<sessionId>-<sequenceHash>"
   - name: human-readable from tool names joined by "→"
   - pattern: the tool sequence as comma-separated string
   - procedure: description of the pattern
   - toolName: first tool in the sequence
   - version: "0.1.0"
5. Register SOP via `proceduralVault.register(sop)`
