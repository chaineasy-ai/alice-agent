package org.cland.alice.memory.dreaming

import org.cland.alice.memory.core.Knowledge
import org.cland.alice.memory.vault.InMemorySemanticVault
import spock.lang.Specification

class ConflictResolverSpec extends Specification {

  InMemorySemanticVault semanticVault
  ConflictResolver resolver

  static final String COLLECTION = "_dreaming_facts"

  def setup() {
    semanticVault = new InMemorySemanticVault()
    resolver = new ConflictResolver(semanticVault)
  }

  def "newer fact with same content as existing - old gets deprecated, new stored"() {
    given: "existing knowledge in vault"
    def oldTime = 1000L
    def oldKnowledge = Knowledge.builder()
        .knowledgeId("old-1")
        .content("db_timeout is 30s")
        .source("previous-session")
        .collection(COLLECTION)
        .createdAt(oldTime)
        .build()
    semanticVault.store(COLLECTION, oldKnowledge)

    and: "a newer fact with the same content (clearly newer, beyond 1s tolerance)"
    def newTime = 5000L
    def fact = new DreamingFact("fact-1", "db_timeout is 30s", "session-1", 1, newTime, 0.9)

    when:
    def result = resolver.resolve([fact], "session-1")

    then:
    result.factsProcessed() == 1
    result.newFacts() == 0
    result.deprecatedFacts() == 1
    result.manualReviewFacts() == 0

    // Old knowledge marked as DEPRECATED
    def allFacts = semanticVault.getAll(COLLECTION)
    allFacts.size() == 2
    allFacts.any { it.content().contains("(DEPRECATED)") && it.content().contains("db_timeout is 30s") }
    allFacts.any { it.content() == "db_timeout is 30s" && !it.content().contains("DEPRECATED") }
  }

  def "no conflict - new fact stored as ACTIVE"() {
    given: "a fact with no matching content in vault"
    def fact = new DreamingFact("fact-2", "server hostname is prod-01", "session-1", 1, 1000L, 0.9)

    when:
    def result = resolver.resolve([fact], "session-1")

    then:
    result.factsProcessed() == 1
    result.newFacts() == 1
    result.deprecatedFacts() == 0
    result.manualReviewFacts() == 0

    def allFacts = semanticVault.getAll(COLLECTION)
    allFacts.size() == 1
    allFacts[0].content() == "server hostname is prod-01"
  }

  def "equal timestamps - both marked MANUAL_REVIEW"() {
    given: "existing knowledge"
    def timestamp = 1000L
    def oldKnowledge = Knowledge.builder()
        .knowledgeId("old-2")
        .content("max_retries is 3")
        .source("previous-session")
        .collection(COLLECTION)
        .createdAt(timestamp)
        .build()
    semanticVault.store(COLLECTION, oldKnowledge)

    and: "a fact with same timestamp (within 1s tolerance)"
    def fact = new DreamingFact("fact-3", "max_retries is 3", "session-1", 1, timestamp, 0.9)

    when:
    def result = resolver.resolve([fact], "session-1")

    then:
    result.manualReviewFacts() == 1
    result.newFacts() == 0

    def allFacts = semanticVault.getAll(COLLECTION)
    allFacts.any { it.content().contains("(MANUAL_REVIEW)") }
  }

  def "low confidence fact (< 0.5) is skipped"() {
    given: "a low confidence fact"
    def fact = new DreamingFact("fact-4", "maybe the sky is blue", "session-1", 1, 1000L, 0.3)

    when:
    def result = resolver.resolve([fact], "session-1")

    then:
    result.factsProcessed() == 0
    result.newFacts() == 0
    semanticVault.count(COLLECTION) == 0
  }

  def "empty fact list results in no-op"() {
    when:
    def result = resolver.resolve([], "session-1")

    then:
    result.factsProcessed() == 0
    result.newFacts() == 0
    result.deprecatedFacts() == 0
    result.manualReviewFacts() == 0
  }

  def "existing fact is newer than incoming fact with same content - manual review"() {
    given: "existing knowledge is newer"
    def newTime = 2000L
    def existingKnowledge = Knowledge.builder()
        .knowledgeId("old-3")
        .content("api_version is v2")
        .source("previous-session")
        .collection(COLLECTION)
        .createdAt(newTime)
        .build()
    semanticVault.store(COLLECTION, existingKnowledge)

    and: "an older fact with same content"
    def fact = new DreamingFact("fact-5", "api_version is v2", "session-1", 1, 1000L, 0.9)

    when:
    def result = resolver.resolve([fact], "session-1")

    then:
    // Both timestamps are within 1s tolerance of each other → MANUAL_REVIEW
    result.manualReviewFacts() == 1
    result.deprecatedFacts() == 0
    result.newFacts() == 0
  }

  def "whitespace normalization works for content matching"() {
    given: "existing knowledge with extra spaces"
    def oldKnowledge = Knowledge.builder()
        .knowledgeId("old-4")
        .content("  key   is   value  ")
        .source("previous-session")
        .collection(COLLECTION)
        .createdAt(1000L)
        .build()
    semanticVault.store(COLLECTION, oldKnowledge)

    and: "a newer fact with normalized whitespace (more than 1s apart)"
    def fact = new DreamingFact("fact-6", "key is value", "session-1", 1, 5000L, 0.9)

    when:
    def result = resolver.resolve([fact], "session-1")

    then:
    result.deprecatedFacts() == 1  // Content matches after normalization
    result.newFacts() == 0
  }
}
