package org.cland.alice.core.agent

import spock.lang.Specification

class AgentContextSpec extends Specification {

    def "should create context with default values"() {
        when:
        def ctx = new AgentContext()

        then:
        ctx.sessionId() != null
        ctx.iteration() == 0
        ctx.maxIterations() == AgentContext.DEFAULT_MAX_ITERATIONS
        ctx.currentPhase() == AgentContext.Phase.START
        !ctx.containsKey("prompt")
    }

    def "should increment iteration"() {
        given:
        def ctx = new AgentContext()

        when:
        def v1 = ctx.incrementIteration()
        def v2 = ctx.incrementIteration()

        then:
        v1 == 1
        v2 == 2
        ctx.iteration() == 2
    }

    def "should detect max iterations"() {
        given:
        def ctx = new AgentContext(3)

        when: "increment to max"
        ctx.incrementIteration() // 1
        ctx.incrementIteration() // 2
        ctx.incrementIteration() // 3

        then:
        ctx.isMaxIterationsReached()
    }

    def "should transition phases correctly"() {
        given:
        def ctx = new AgentContext()

        when:
        ctx.transitionTo(AgentContext.Phase.PERCEIVING)

        then:
        ctx.currentPhase() == AgentContext.Phase.PERCEIVING
    }

    def "should reject invalid phase transitions"() {
        given:
        def ctx = new AgentContext()

        when:
        ctx.transitionTo(AgentContext.Phase.FINISH)

        then:
        thrown(IllegalStateException)
    }

    def "should store and retrieve attributes"() {
        given:
        def ctx = new AgentContext()

        when:
        ctx.put("key1", "value1")
        ctx.put("key2", 42)

        then:
        ctx.get("key1") == "value1"
        ctx.get("key2") == 42
        ctx.containsKey("key1")
        !ctx.containsKey("nonexistent")
    }

    def "should build thought chain"() {
        given:
        def ctx = new AgentContext()

        when:
        ctx.appendThought("First thought")
        ctx.appendThought("Second thought")

        then:
        ctx.thoughtChain().contains("First thought")
        ctx.thoughtChain().contains("Second thought")
        ctx.thoughtChain().contains("---")
    }
}
