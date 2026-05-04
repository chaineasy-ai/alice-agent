package org.cland.alice.core.agent

import org.cland.alice.core.agent.lifecycle.Action
import org.cland.alice.core.agent.result.StepResult
import spock.lang.Specification

class StepResultSpec extends Specification {

    def "should create Continue result"() {
        given:
        def action = Action.finish()

        when:
        def result = new StepResult.Continue(action)

        then:
        result.nextAction() == action
        result.observation() == null
    }

    def "should create Finish result"() {
        when:
        def result = new StepResult.Finish("Final answer")

        then:
        result.answer() == "Final answer"
        result.toString().contains("Finish")
    }

    def "should create Failure result"() {
        when:
        def result = new StepResult.Failure("Something broke")

        then:
        result.errorMessage() == "Something broke"
        result.cause() == null
    }

    def "should create Failure with cause"() {
        given:
        def cause = new RuntimeException("DB error")

        when:
        def result = new StepResult.Failure("DB failed", cause)

        then:
        result.errorMessage() == "DB failed"
        result.cause().message == "DB error"
    }

    def "should use factory methods"() {
        expect:
        StepResult.cont(Action.finish()) instanceof StepResult.Continue
        StepResult.finish("done") instanceof StepResult.Finish
        StepResult.fail("err") instanceof StepResult.Failure
    }

    def "sealed class should not allow external subclasses"() {
        when:
        // 尝试获取的 permits 应该只有 Continue, Finish, Failure
        def permits = StepResult.class.getPermittedSubclasses()

        then:
        permits.length == 3
        permits.toList().contains(StepResult.Continue)
        permits.toList().contains(StepResult.Finish)
        permits.toList().contains(StepResult.Failure)
    }
}
