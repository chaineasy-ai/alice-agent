package org.cland.alice.core.agent.pipeline

import com.fasterxml.jackson.databind.ObjectMapper
import org.cland.alice.core.agent.kernel.Inferencer
import org.cland.alice.core.agent.kernel.InferRequest
import org.cland.alice.core.agent.kernel.InferRequest.ToolSpec
import org.cland.alice.core.agent.kernel.ModelObservation
import org.cland.alice.core.agent.kernel.ModelStatus
import org.cland.alice.model.Call
import org.cland.alice.model.Model
import org.cland.alice.model.ModelProvider
import org.cland.alice.model.ModelSupplier
import spock.lang.Specification
import spock.lang.Title

/**
 * 文本 LLM pipeline（actor kind，六段链）测试。
 *
 * 覆盖 §6.2 语义翻译：厂商 finish_reason → 内核 ModelStatus、reasoning/tool_calls 解码、tools schema 序列化。
 * 通过 ModelProvider stub 供应商驱动 ④ Transport。
 */
@Title("TextLlmPipeline — 六段 actor pipeline")
class TextLlmPipelineSpec extends Specification {

    private static final Call.TokenUsage TU = new Call.TokenUsage(10, 5, 15)

    def cleanup() {
        ModelProvider.reset()
    }

    private String raw(String finishReason, String reasoning = null) {
        def sb = new StringBuilder()
        sb.append('{"id":"r1","choices":[{"index":0,"message":{"role":"assistant","content":"ok"')
        if (reasoning != null) {
            sb.append(',"reasoning_content":"').append(reasoning).append('"')
        }
        sb.append('},"finish_reason":"').append(finishReason).append('"}]}')
        return sb.toString()
    }

    /** 注册 stub 供应商 + 模型，返回其 name。 */
    private String registerSupplier(Closure respond) {
        def supplier = Stub(ModelSupplier) {
            request(_ as Call) >> { Call c -> respond(c) as Call.Response }
        }
        ModelProvider.getInstance()
            .registerSupplier(supplier)
            .registerModel(Model.builder()
                .modelId("pipe-model")
                .supplierName(supplier.name())
                .capability(Model.Capability.FUNCTION_CALL)
                .pricing(new Model.Pricing(0, 0))
                .build())
        return supplier.name()
    }

    private InferRequest req(Map params = [:], List<ToolSpec> tools = []) {
        new InferRequest("pipe-model", "sys", "user q", params, tools)
    }

    def "should map stop to CONTENT with content"() {
        given:
        registerSupplier { Call c -> Call.Response.textOnly("Final answer", TU, ["raw": raw("stop")]) }
        def pipeline = new TextLlmPipeline()

        when:
        def obs = pipeline.infer(req()).result()

        then:
        obs.status() == ModelStatus.CONTENT
        obs.content() == "Final answer"
        obs.hasToolCalls() == false
        obs.detail() == null
    }

    def "should decode reasoning_content from raw metadata with escape decoding"() {
        given:
        def rawJson = raw("stop", "step one\\nstep \\\"two\\\"\\ttab")
        registerSupplier { Call c -> Call.Response.textOnly("", TU, ["raw": rawJson]) }
        def pipeline = new TextLlmPipeline()

        when:
        def obs = pipeline.infer(req()).result()

        then:
        obs.reasoning() == 'step one\nstep "two"\ttab'
    }

    def "should map tool_calls finish to TOOL_CALLS with decisions"() {
        given:
        def toolCalls = [new Call.ToolCall("mock_op", '{"msg": "hi"}')]
        registerSupplier { Call c ->
            new Call.Response("I will call.", TU, ["raw": raw("tool_calls")], toolCalls)
        }
        def pipeline = new TextLlmPipeline()

        when:
        def obs = pipeline.infer(req()).result()

        then:
        obs.status() == ModelStatus.TOOL_CALLS
        obs.hasToolCalls()
        obs.toolCalls().size() == 1
        obs.toolCalls()[0].name() == "mock_op"
        obs.toolCalls()[0].argumentsJson() == '{"msg": "hi"}'
    }

    def "should map non-natural finish reason to TRUNCATED with detail"() {
        given:
        registerSupplier { Call c -> Call.Response.textOnly("partial", TU, ["raw": raw("length")]) }
        def pipeline = new TextLlmPipeline()

        when:
        def obs = pipeline.infer(req()).result()

        then:
        obs.status() == ModelStatus.TRUNCATED
        obs.detail() == "length"
        obs.content() == "partial"
    }

    def "should serialize tools schema into transport parameters"() {
        given:
        def seen = []
        registerSupplier { Call c ->
            seen << c.payload().parameters()
            return Call.Response.textOnly("ok", TU, ["raw": raw("stop")])
        }
        def schema = new ObjectMapper().readTree('{"type":"object"}')
        def pipeline = new TextLlmPipeline()

        when:
        def obs = pipeline.infer(req([:], [new ToolSpec("web_search", "Search the web", schema)])).result()

        then:
        obs.status() == ModelStatus.CONTENT
        seen.size() == 1
        def tools = seen[0]["tools"] as List
        tools.size() == 1
        (tools[0] as Map)["type"] == "function"
        ((tools[0] as Map)["function"] as Map)["name"] == "web_search"
    }

    def "should forward thinking parameters to transport"() {
        given:
        def seen = []
        registerSupplier { Call c ->
            seen << c.payload().parameters()
            return Call.Response.textOnly("ok", TU, ["raw": raw("stop")])
        }
        def pipeline = new TextLlmPipeline()

        when:
        pipeline.infer(req([enable_thinking: true, reasoning_effort: "high"])).result()

        then:
        seen[0]["enable_thinking"] == true
        seen[0]["reasoning_effort"] == "high"
    }

    def "transport exceptions propagate synchronously for caller error semantics"() {
        given:
        registerSupplier { Call c -> throw new RuntimeException("network down") }
        def pipeline = new TextLlmPipeline()

        when:
        pipeline.infer(req())

        then:
        thrown(RuntimeException)
    }

    def "implements the Inferencer contract with immediate future"() {
        given:
        registerSupplier { Call c -> Call.Response.textOnly("ok", TU, ["raw": raw("stop")]) }

        expect:
        new TextLlmPipeline() instanceof Inferencer
        new TextLlmPipeline().infer(req()).succeeded()
    }
}
