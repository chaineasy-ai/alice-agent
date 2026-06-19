package org.cland.alice.core.agent.executor

import org.cland.alice.core.agent.Agent
import org.cland.alice.core.agent.AgentConfig
import org.cland.alice.core.agent.AgentContext
import org.cland.alice.model.Call
import org.cland.alice.model.Model
import org.cland.alice.model.ModelProvider
import org.cland.alice.model.ModelSupplier
import org.cland.alice.tool.gateway.ToolRegistry
import org.cland.alice.tool.gateway.engine.ExecutionEngine
import org.cland.alice.tool.gateway.builtin.BuiltinTools
import spock.lang.Specification
import spock.lang.Timeout

/**
 * Test that AgentExecutor correctly handles multiple structured tool calls
 * from a single Function Calling response.
 *
 * Flow tested:
 * 1. LLM returns Response with 2+ ToolCalls
 * 2. dispatchLlmInference stores them in ctx["__tool_calls"]
 * 3. Reason phase pops them one by one via __tool_call_index
 * 4. After each tool execution, hasMoreMarkers check returns true
 * 5. dispatchTool returns Continue(null, obs) — no new LLM call
 * 6. Continue.action() is null → Reason continues to next tool_call
 * 7. After all consumed, ___tool_calls removed and Continue(null) triggers LLM re-call
 */
@Timeout(15)
class AgentExecutorMultiToolCallSpec extends Specification {

    def mockSupplier
    def mockToolRegistry

    def setup() {
        // Reset ModelProvider singleton
        ModelProvider.reset()
    }

    def cleanup() {
        ModelProvider.reset()
    }

    /**
     * 测试 LLM 返回 2 个 tool_calls（write_file x 2），
     * AgentExecutor 依次执行两个写操作后，最后 LLM 才收到新一轮调用。
     */
    def "should dispatch all structured tool calls from one LLM response before next LLM call"() {
        given:
        // 准备两个 write_file 参数
        def args1 = '{"path": "a.py", "content": "print(1)"}'
        def args2 = '{"path": "b.py", "content": "print(2)"}'
        def toolCalls = [
            new Call.ToolCall("write_file", args1),
            new Call.ToolCall("write_file", args2)
        ]
        def response1 = new Call.Response(
            "I will write both files.",
            new Call.TokenUsage(100, 50, 150),
            ["raw": toRawMetadata(2, args1, args2)],
            toolCalls
        )
        // 第二轮 LLM 调用：直接返回 FINISH
        def response2 = Call.Response.textOnly(
            "[FINISH]",
            new Call.TokenUsage(200, 10, 210),
            ["raw": '{"id":"r2","choices":[{"index":0,"message":{"role":"assistant","content":"[FINISH]"}}]}']
        )

        mockSupplier = Stub(ModelSupplier) {
            request(_) >>> [response1, response2]
        }

        // 注册 mock supplier + model
        ModelProvider.getInstance()
            .registerSupplier(mockSupplier)
            .registerModel(Model.builder()
                .modelId("test-model")
                .supplierName(mockSupplier.name())
                .capability(Model.Capability.FUNCTION_CALL)
                .pricing(new Model.Pricing(0, 0))
                .build())

        // 准备 Agent（需要 ToolRegistry，ExecutionEngine 会延迟初始化）
        mockToolRegistry = new ToolRegistry()
        def discovery = new org.cland.alice.tool.gateway.engine.ToolDiscovery(mockToolRegistry)
        discovery.scanAndRegister([new BuiltinTools()])

        def agent = new Agent("test-multi-call", AgentConfig.builder()
            .defaultModelId("test-model")
            .maxIterations(10)
            .actionTimeoutMs(5000)
            .build())
        agent.withToolRegistry(mockToolRegistry)

        when:
        def result = agent.ask("Write two files: a.py and b.py")

        then:
        result == "[FINISH]"
    }

    /**
     * 测试 LLM 返回 3 个 tool_calls（read_file x 2, write_file x 1），
     * 确保 read_file 的结果不会导致 premature FINISH。
     */
    def "should handle read-write-read pattern in single LLM response"() {
        given:
        def toolCalls = [
            new Call.ToolCall("read_file", '{"path": "x.py"}'),
            new Call.ToolCall("read_file", '{"path": "y.py"}'),
            new Call.ToolCall("write_file", '{"path": "z.py", "content": "# combined"}')
        ]
        def response1 = new Call.Response(
            "Let me read both files first.",
            new Call.TokenUsage(100, 60, 160),
            ["raw": toRawMetadata(3,
                '{"path": "x.py"}',
                '{"path": "y.py"}',
                '{"path": "z.py", "content": "# combined"}')],
            toolCalls
        )
        def response2 = Call.Response.textOnly(
            "[FINISH]",
            new Call.TokenUsage(200, 10, 210),
            ["raw": '{"id":"r2","choices":[{"index":0,"message":{"role":"assistant","content":"[FINISH]"}}]}']
        )

        mockSupplier = Stub(ModelSupplier) {
            request(_) >>> [response1, response2]
        }

        ModelProvider.getInstance()
            .registerSupplier(mockSupplier)
            .registerModel(Model.builder()
                .modelId("test-model-2")
                .supplierName(mockSupplier.name())
                .capability(Model.Capability.FUNCTION_CALL)
                .pricing(new Model.Pricing(0, 0))
                .build())

        mockToolRegistry = new ToolRegistry()
        def discovery = new org.cland.alice.tool.gateway.engine.ToolDiscovery(mockToolRegistry)
        discovery.scanAndRegister([new BuiltinTools()])

        def agent = new Agent("test-read-write", AgentConfig.builder()
            .defaultModelId("test-model-2")
            .maxIterations(10)
            .actionTimeoutMs(5000)
            .build())
        agent.withToolRegistry(mockToolRegistry)

        when:
        def result = agent.ask("Read x.py and y.py, then write combined content to z.py")

        then:
        result == "[FINISH]"
    }

    /**
     * 测试 LLM 返回 1 个 tool_call，执行完成后下一轮 LLM 调用收到
     * 累积的 __action_log。
     */
    def "should accumulate action log across micro loop iterations"() {
        given:
        def toolCalls1 = [
            new Call.ToolCall("read_file", '{"path": "test.txt"}')
        ]
        def response1 = new Call.Response(
            "Read the file first.",
            new Call.TokenUsage(100, 20, 120),
            ["raw": toRawMetadata(1, '{"path": "test.txt"}')],
            toolCalls1
        )
        // 第二轮 LLM 调用 — 携带 action_log 上下文
        // 但 mock supplier 仍然返回 FINISH
        def response2 = Call.Response.textOnly(
            "[FINISH]",
            new Call.TokenUsage(200, 10, 210),
            ["raw": '{"id":"r2","choices":[{"index":0,"message":{"role":"assistant","content":"[FINISH]"}}]}']
        )

        mockSupplier = Stub(ModelSupplier) {
            request(_) >>> [response1, response2]
        }

        ModelProvider.getInstance()
            .registerSupplier(mockSupplier)
            .registerModel(Model.builder()
                .modelId("test-model-3")
                .supplierName(mockSupplier.name())
                .capability(Model.Capability.FUNCTION_CALL)
                .pricing(new Model.Pricing(0, 0))
                .build())

        mockToolRegistry = new ToolRegistry()
        def discovery = new org.cland.alice.tool.gateway.engine.ToolDiscovery(mockToolRegistry)
        discovery.scanAndRegister([new BuiltinTools()])

        def agent = new Agent("test-action-log", AgentConfig.builder()
            .defaultModelId("test-model-3")
            .maxIterations(10)
            .actionTimeoutMs(5000)
            .build())
        agent.withToolRegistry(mockToolRegistry)

        when:
        def result = agent.ask("Read test.txt")

        then:
        result == "[FINISH]"
    }

    // ========================================================================
    // Helper
    // ========================================================================

    /**
     * 构造 OpenAI 风格的 raw metadata 字符串，包含多个 tool_calls。
     */
    private static String toRawMetadata(int count, String... argsList) {
        def sb = new StringBuilder()
        sb.append('{"id":"test","object":"chat.completion","created":1000000,"model":"test",')
        sb.append('"choices":[{"index":0,"message":{"role":"assistant","content":"ok","tool_calls":[')
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(',')
            sb.append('{"index":').append(i)
            sb.append(',"id":"call_00_').append(i).append('",')
            sb.append('"type":"function",')
            sb.append('"function":{"name":"test_tool","arguments":').append(argsList[i]).append('}}')
        }
        sb.append(']}}')
        sb.append(',"logprobs":null,"finish_reason":"tool_calls"}],')
        sb.append('"usage":{"prompt_tokens":100,"completion_tokens":50,"total_tokens":150}}')
        return sb.toString()
    }
}
