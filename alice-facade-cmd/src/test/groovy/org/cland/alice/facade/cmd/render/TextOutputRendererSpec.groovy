package org.cland.alice.facade.cmd.render

import org.cland.alice.core.agent.lifecycle.Action
import org.cland.alice.core.agent.lifecycle.Observation
import org.cland.alice.core.agent.result.StepResult
import org.cland.alice.facade.cmd.config.RunConfig
import spock.lang.Specification

class TextOutputRendererSpec extends Specification {

    def renderer = new TextOutputRenderer()
    def config = RunConfig.builder().task("test").verbose(true).build()
    PrintStream origOut
    PrintStream origErr

    def setup() {
        origOut = System.out
        origErr = System.err
    }

    def cleanup() {
        System.setOut(origOut)
        System.setErr(origErr)
    }

    def captureOut(Closure<?> closure) {
        def outStream = new ByteArrayOutputStream()
        System.setOut(new PrintStream(outStream))
        closure()
        return outStream.toString()
    }

    def captureErr(Closure<?> closure) {
        def errStream = new ByteArrayOutputStream()
        System.setErr(new PrintStream(errStream))
        closure()
        return errStream.toString()
    }

    def "should render Continue step"() {
        given:
        def action = Action.llmInference("gpt-4o", "hello")
        def result = new StepResult.Continue(action)

        when:
        def output = captureOut { renderer.render(result, config) }

        then:
        output.contains("LLM_INFERENCE")
        output.contains("gpt-4o")
    }

    def "should render Continue with observation in verbose"() {
        given:
        def action = Action.finish()
        def obs = Observation.success("All good")
        def result = new StepResult.Continue(action, obs)

        when:
        def output = captureOut { renderer.render(result, config) }

        then:
        output.contains("FINISH")
        output.contains("All good")
    }

    def "should render Continue without observation in non-verbose"() {
        given:
        def nonVerboseConfig = RunConfig.builder().task("test").verbose(false).build()
        def action = Action.finish()
        def obs = Observation.success("Should not show")
        def result = new StepResult.Continue(action, obs)

        when:
        def output = captureOut { renderer.render(result, nonVerboseConfig) }

        then:
        output.contains("FINISH")
        !output.contains("Should not show")
    }

    def "should render Final summary"() {
        when:
        def output = captureOut { renderer.renderFinal("The answer is 42", config) }

        then:
        output.contains("The answer is 42")
        output.contains("Final Answer")
    }

    def "should render error message"() {
        when:
        def output = captureErr { renderer.renderError("Something went wrong", config) }

        then:
        output.contains("Something went wrong")
    }

    def "should handle null step result gracefully"() {
        when:
        def output = captureOut { renderer.render(null, config) }

        then:
        output.isEmpty()
    }

    def "should render Failure step"() {
        given:
        def result = new StepResult.Failure("DB connection error")

        when:
        def output = captureErr { renderer.render(result, config) }

        then:
        output.contains("DB connection error")
    }

    def "should render Failure with cause in verbose"() {
        given:
        def cause = new RuntimeException("connection timeout")
        def result = new StepResult.Failure("DB error", cause)

        when:
        def output = captureErr { renderer.render(result, config) }

        then:
        output.contains("DB error")
    }

    def "should render Continue with tool call action"() {
        given:
        def action = Action.toolCall("read_file", ["path": "/tmp/test.txt"])
        def result = new StepResult.Continue(action)

        when:
        def output = captureOut { renderer.render(result, config) }

        then:
        output.contains("TOOL_CALL")
        output.contains("read_file")
    }
}
