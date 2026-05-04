package org.cland.alice.core.agent

import org.cland.alice.core.agent.lifecycle.Action
import spock.lang.Specification

class ActionSpec extends Specification {

    def "should create LLM inference action"() {
        when:
        def action = Action.llmInference("gpt-4", "Hello")

        then:
        action.type() == Action.Type.LLM_INFERENCE
        action.target() == "gpt-4"
        action.parameters()["prompt"] == "Hello"
        action.actionId() != null
    }

    def "should create FINISH action"() {
        when:
        def action = Action.finish()

        then:
        action.type() == Action.Type.FINISH
        action.target() == "FINISH"
    }

    def "should create TOOL_CALL action"() {
        given:
        def params = [arg1: "value1"]

        when:
        def action = Action.toolCall("myTool", params)

        then:
        action.type() == Action.Type.TOOL_CALL
        action.target() == "myTool"
        action.parameters()["arg1"] == "value1"
    }

    def "should create REVISION action"() {
        when:
        def action = Action.revision("Something went wrong")

        then:
        action.type() == Action.Type.REVISION
        action.target() == "REVISION"
        action.parameters()["feedback"] == "Something went wrong"
    }

    def "should use custom builder"() {
        when:
        def action = Action.builder()
            .type(Action.Type.OBSERVE)
            .target("env")
            .parameter("key", "val")
            .thought("I should observe")
            .build()

        then:
        action.type() == Action.Type.OBSERVE
        action.target() == "env"
        action.thought() == "I should observe"
        action.parameters()["key"] == "val"
    }

    def "should have unique action IDs"() {
        when:
        def a1 = Action.finish()
        def a2 = Action.finish()

        then:
        a1.actionId() != a2.actionId()
    }
}
