package org.cland.alice.facade.cli.render

import org.cland.alice.agent.proto.codec.EventCodec
import org.cland.alice.agent.proto.event.StepEvent
import org.cland.alice.agent.proto.event.StepEventType
import org.cland.alice.core.agent.lifecycle.Action
import org.cland.alice.core.agent.lifecycle.Observation
import org.cland.alice.core.agent.result.StepResult
import org.cland.alice.facade.cli.config.RunConfig
import spock.lang.Specification
import spock.lang.Title

/**
 * 测试 {@link JsonOutputRenderer}：`--json` 输出为**协议 v1 StepEvent 帧（JSONL）**。
 *
 * 口径（2026-09-19）：不再自定义 JSON 结构，与 stdio/HTTP 共用同一 schema。
 */
@Title("JsonOutputRenderer — 协议帧输出")
class JsonOutputRendererSpec extends Specification {

    def renderer = new JsonOutputRenderer("sess-1", "trace-1")
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
        System.setOut(new PrintStream(outStream, true, "UTF-8"))
        closure()
        return outStream.toString("UTF-8")
    }

    static List<StepEvent> frames(String out) {
        return out.readLines().findAll { it.trim() }.collect { EventCodec.decode(it) }
    }

    // ========================================================================
    // 步骤映射
    // ========================================================================

    def "Continue(action) ⇒ TOOL_CALL 帧（含 session/trace/seq）"() {
        given:
        def action = Action.llmInference("gpt-4o", "analyze")

        when:
        def out = captureOut { renderer.render(new StepResult.Continue(action), config) }

        then:
        def frames = frames(out)
        frames.size() == 1
        frames[0].v() == 1
        frames[0].type() == StepEventType.TOOL_CALL
        frames[0].sessionId() == "sess-1"
        frames[0].traceId() == "trace-1"
        frames[0].seq() == 0L
        frames[0].payload().tool == "gpt-4o"
        frames[0].payload().type == "LLM_INFERENCE"
        frames[0].payload().args.prompt == "analyze" // 协议 TOOL_CALL：args 必带
    }

    def "Continue(action+observation) ⇒ TOOL_CALL + TOOL_RESULT（seq 递增）"() {
        given:
        def action = Action.finish()
        def obs = Observation.success("Task done")

        when:
        def out = captureOut { renderer.render(new StepResult.Continue(action, obs), config) }

        then:
        def frames = frames(out)
        frames*.type() == [StepEventType.TOOL_CALL, StepEventType.TOOL_RESULT]
        frames[0].seq() == 0L
        frames[1].seq() == 1L
        frames[1].payload().status == "SUCCESS"
        frames[1].payload().summary == "Task done"
    }

    def "verbose 开关：thought 只在 verbose 下带（体积可控）"() {
        given:
        def action = Action.builder().type(Action.Type.LLM_INFERENCE).target("gpt-4o").thought("先想一下").build()

        when:
        def verboseOut = captureOut { renderer.render(new StepResult.Continue(action), config) }
        def quietOut = captureOut { renderer.render(new StepResult.Continue(action), nonVerboseConfig) }

        then:
        frames(verboseOut)[0].payload().thought == "先想一下"
        frames(quietOut)[0].payload().thought == null
    }

    def "Finish ⇒ SUMMARY 帧"() {
        when:
        def out = captureOut { renderer.render(new StepResult.Finish("最终答案", "摘要"), config) }

        then:
        def frames = frames(out)
        frames*.type() == [StepEventType.SUMMARY]
        frames[0].payload().text == "最终答案"
        frames[0].payload().summary == "摘要"
    }

    def "Failure ⇒ ERROR 帧（含 cause）"() {
        given:
        def fail = new StepResult.Failure("boom", new RuntimeException("根因"))

        when:
        def out = captureOut { renderer.render(fail, config) }

        then:
        def frames = frames(out)
        frames*.type() == [StepEventType.ERROR]
        frames[0].payload().message == "boom"
        frames[0].payload().cause == "根因"
    }

    // ========================================================================
    // 收口
    // ========================================================================

    def "renderFinal ⇒ SUMMARY + DONE（收口帧）"() {
        when:
        def out = captureOut { renderer.renderFinal("完成", config) }

        then:
        def frames = frames(out)
        frames*.type() == [StepEventType.SUMMARY, StepEventType.DONE]
        frames[1].payload().isEmpty()
    }

    def "renderError ⇒ ERROR + DONE"() {
        when:
        def out = captureOut { renderer.renderError("炸了", config) }

        then:
        def frames = frames(out)
        frames*.type() == [StepEventType.ERROR, StepEventType.DONE]
        frames[0].payload().message == "炸了"
    }

    def "输出为合法 JSONL（逐行可解码、无多余行）"() {
        when:
        def out = captureOut {
            renderer.render(new StepResult.Continue(Action.llmInference("m", "p")), config)
            renderer.renderFinal("done", config)
        }

        then:
        def lines = out.readLines().findAll { it.trim() }
        lines.size() == 3
        lines.every { EventCodec.decode(it) != null }
    }
}
