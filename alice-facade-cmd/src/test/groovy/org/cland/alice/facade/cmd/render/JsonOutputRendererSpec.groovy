package org.cland.alice.facade.cmd.render

import org.cland.alice.core.agent.lifecycle.Action
import org.cland.alice.core.agent.lifecycle.Observation
import org.cland.alice.core.agent.result.StepResult
import org.cland.alice.facade.cmd.config.RunConfig
import spock.lang.Specification

class JsonOutputRendererSpec extends Specification {

    def renderer = new JsonOutputRenderer()
    def config = RunConfig.builder().task("test").jsonOutput(true).verbose(true).build()
    def nonVerboseConfig = RunConfig.builder().task("test").jsonOutput(true).verbose(false).build()
    PrintStream origOut

    def setup() {
        origOut = System.out
    }

    def cleanup() {
        System.setOut(origOut)
    }

    def captureOut(Closure<?> closure) {
        def outStream = new ByteArrayOutputStream()
        System.setOut(new PrintStream(outStream))
        closure()
        return outStream.toString()
    }

    def "should render Continue step as valid JSON"() {
        given:
        def action = Action.llmInference("gpt-4o", "analyze")
        def result = new StepResult.Continue(action)

        when:
        def json = captureOut { renderer.render(result, config) }

        then:
        json.contains('"type":"step"')
        json.contains('"LLM_INFERENCE"')
        json.contains('"gpt-4o"')
    }

    def "should render Continue with observation"() {
        given:
        def action = Action.finish()
        def obs = Observation.success("Task done")
        def result = new StepResult.Continue(action, obs)

        when:
        def json = captureOut { renderer.render(result, config) }

        then:
        json.contains('"observation"')
        json.contains('"SUCCESS"')
        json.contains('"Task done"')
    }

    def "should render Final result"() {
        when:
        def json = captureOut { renderer.renderFinal("The answer is 42", config) }

        then:
        json.contains('"type":"final"')
        json.contains('"status":"completed"')
        json.contains('"The answer is 42"')
    }

    def "should render error"() {
        when:
        def json = captureOut { renderer.renderError("Failed to connect", config) }

        then:
        json.contains('"type":"error"')
        json.contains('"status":"error"')
        json.contains('"Failed to connect"')
    }

    def "should render Finish step in line"() {
        given:
        def result = new StepResult.Finish("Done answer")

        when:
        def json = captureOut { renderer.render(result, config) }

        then:
        json.contains('"phase":"finish"')
        json.contains('"Done answer"')
    }

    def "should render Failure step"() {
        given:
        def result = new StepResult.Failure("Something broke")

        when:
        def json = captureOut { renderer.render(result, config) }

        then:
        json.contains('"phase":"failure"')
        json.contains('"Something broke"')
    }

    def "should render Failure with cause"() {
        given:
        def cause = new RuntimeException("underlying cause")
        def result = new StepResult.Failure("Error", cause)

        when:
        def json = captureOut { renderer.render(result, config) }

        then:
        json.contains('"phase":"failure"')
        json.contains('"cause"')
        json.contains('"underlying cause"')
    }

    def "should include thought in verbose mode"() {
        given:
        def action = Action.builder()
            .type(Action.Type.LLM_INFERENCE)
            .target("gpt-4o")
            .thought("I should analyze the logs first")
            .parameter("prompt", "analyze")
            .build()
        def result = new StepResult.Continue(action)

        when:
        def json = captureOut { renderer.render(result, config) }

        then:
        json.contains('"thought"')
        json.contains('analyze the logs')
    }

    def "should NOT include thought in non-verbose mode"() {
        given:
        def action = Action.builder()
            .type(Action.Type.LLM_INFERENCE)
            .target("gpt-4o")
            .thought("I should think deeply")
            .parameter("prompt", "hello")
            .build()
        def result = new StepResult.Continue(action)

        when:
        def json = captureOut { renderer.render(result, nonVerboseConfig) }

        then:
        !json.contains('"thought"')
    }

    def "should handle null step result gracefully"() {
        when:
        def json = captureOut { renderer.render(null, config) }

        then:
        json.isEmpty()
    }

    def "OutputRenderer factory creates correct type"() {
        expect:
        OutputRenderer.create(
            RunConfig.builder().task("test").jsonOutput(false).build()
        ) instanceof TextOutputRenderer

        OutputRenderer.create(
            RunConfig.builder().task("test").jsonOutput(true).build()
        ) instanceof JsonOutputRenderer
    }

    def "should produce valid JSON syntax"() {
        given:
        def action = Action.toolCall("ls", ["dir": "/tmp"])
        def result = new StepResult.Continue(action)

        when:
        def json = captureOut { renderer.render(result, config) }

        then:
        json.trim().startsWith("{")
        json.trim().endsWith("}")
    }
}
