---

title: "ConflictResolver Interface Contract"
summary: "Public API contract for the ConflictResolver component"
read_when:
  - "implementing or consuming the ConflictResolver"
scope:
  - "alice-memory-vault"
status: "active"
updated: "2026-06-15"
---

# ConflictResolver Interface Contract

## Package
`org.cland.alice.memory.dreaming`

## Related Contracts
- [DreamingEngine](./DreamingEngine-contract.md)

## ConflictResolver API

```java
public final class ConflictResolver {
    public ConflictResolver(SemanticVault semanticVault);

    /**
     * Resolve a batch of facts against existing SemanticVault knowledge.
     * Uses collection="_dreaming_facts" for fact storage.
     *
     * @param facts   Extracted facts from PromptMelter
     * @param sessionId Source session (for provenance tracking)
     * @return ResolveResult with counts of new/deprecated/conflicted facts
     */
    public ResolveResult resolve(List<DreamingFact> facts, String sessionId);

    public record ResolveResult(
        int factsProcessed,
        int newFacts,
        int deprecatedFacts,
        int manualReviewFacts
    ) {}
}
```

## Behavioral Contract

1. For each DreamingFact, search SemanticVault collection "_dreaming_facts"
   for Knowledge with matching content (exact match after whitespace normalization)
2. If match found:
   - Compare timestamp: newer fact wins
   - Old Knowledge marked by appending "(DEPRECATED)" to its content prefix
   - New fact stored as fresh Knowledge with higher createdAt timestamp
3. If no match: store as new ACTIVE Knowledge
4. If timestamps are equal (within 1s tolerance): store new fact but also
   flag existing one by appending "(MANUAL_REVIEW)" — both remain retrievable
