package org.cland.alice.core.agent.executor

import org.cland.alice.core.agent.Agent
import org.cland.alice.core.agent.AgentConfig
import org.cland.alice.model.Call
import org.cland.alice.model.Model
import org.cland.alice.model.ModelProvider
import org.cland.alice.model.ModelSupplier
import org.cland.alice.tool.gateway.ToolRegistry
import org.cland.alice.tool.gateway.metadata.ToolMetadata
import spock.lang.Specification
import spock.lang.Timeout

import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

/**
 * Test that AgentExecutor correctly handles multiple structured tool calls
 * from a single Function Calling response.
 *
 * Uses a MockToolBean with real Java methods on a Spock stub to register
 * mock tools in ToolRegistry, avoiding real file I/O.
 */
@Timeout(15)
class AgentExecutorMultiToolCallSpec extends Specification {

    def mockSupplier
    def mockToolRegistry
    def mockToolBean

    def setup() {
        ModelProvider.reset()
    }

    def cleanup() {
        ModelProvider.reset()
    }

    /**
     * 测试 LLM 返回 2 个 tool_calls（mock tool x 2），
     * AgentExecutor 依次执行两个操作后，最后 LLM 才收到新一轮调用。
     */
    def "should dispatch all structured tool calls from one LLM response before next LLM call"() {
        given:
        def toolCalls = [
            new Call.ToolCall("mock_op", '{"msg": "first"}'),
            new Call.ToolCall("mock_op", '{"msg": "second"}')
        ]
        def response1 = new Call.Response(
            "I will do both operations.",
            new Call.TokenUsage(100, 50, 150),
            ["raw": toRawMetadata("mock_op", '{"msg": "first"}', "mock_op", '{"msg": "second"}')],
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
                .modelId("test-model")
                .supplierName(mockSupplier.name())
                .capability(Model.Capability.FUNCTION_CALL)
                .pricing(new Model.Pricing(0, 0))
                .build())

        // 注册 mock tool — 使用 MockToolBean 的 closure 字段
        mockToolBean = new MockToolBean()
        def calls = []
        mockToolBean.mockOpImpl = { String msg -> calls << msg; "done: $msg" }

        mockToolRegistry = createMockToolRegistry("mock_op", mockToolBean, ["msg"] as String[])

        def agent = new Agent("test-multi-call", AgentConfig.builder()
            .defaultModelId("test-model")
            .maxIterations(10)
            .actionTimeoutMs(5000)
            .build())
        agent.withToolRegistry(mockToolRegistry)

        when:
        def result = agent.ask("Do two operations")

        then:
        result == "[FINISH]"
        // 验证两个工具调用都被执行了（至少一次），证明 multi-call dispatch 工作
        calls.size() >= 2
        calls.subList(0, 2) == ["first", "second"]
    }

    /**
     * 测试 LLM 返回 3 个 tool_calls，包括读和写两种不同类型，
     * AgentExecutor 正确分发全部。
     */
    def "should handle read-write-read pattern in single LLM response"() {
        given:
        def toolCalls = [
            new Call.ToolCall("mock_read", '{"path": "x.txt"}'),
            new Call.ToolCall("mock_write", '{"path": "z.txt", "content": "data"}'),
            new Call.ToolCall("mock_read", '{"path": "y.txt"}')
        ]
        def response1 = new Call.Response(
            "Let me process these files.",
            new Call.TokenUsage(100, 60, 160),
            ["raw": toRawMetadata("mock_read", '{"path": "x.txt"}', "mock_write", '{"path": "z.txt", "content": "data"}', "mock_read", '{"path": "y.txt"}')],
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

        // 注册两个不同类型的 mock tool
        mockToolRegistry = new ToolRegistry()
        def readBean = new MockToolBean()
        def readCalls = []
        readBean.mockOpImpl = { String path -> readCalls << "read:$path"; "content of $path" }

        // 注册 write tool: 双参数提取
        def writeBean2 = new MockToolBean()
        def writeCalls = []
        writeBean2.mockOpImpl2 = { String p, String c -> writeCalls << "write:$p=$c"; "ok" }

        def lookup = MethodHandles.lookup()
        // 注册 read tool: 单参数
        mockToolRegistry.register(ToolMetadata.builder()
            .name("mock_read")
            .description("Mock read tool")
            .inputSchema(createJsonSchema(["path"] as String[]))
            .targetMethod(lookup.findVirtual(MockToolBean, "mockOp",
                MethodType.methodType(String, String)))
            .targetBean(readBean)
            .paramNames(["path"] as String[])
            .build())
        // 注册 write tool: 双参数
        mockToolRegistry.register(ToolMetadata.builder()
            .name("mock_write")
            .description("Mock write tool")
            .inputSchema(createJsonSchema(["path", "content"] as String[]))
            .targetMethod(lookup.findVirtual(MockToolBean, "mockOp2",
                MethodType.methodType(String, String, String)))
            .targetBean(writeBean2)
            .paramNames(["path", "content"] as String[])
            .build())

        def agent = new Agent("test-read-write", AgentConfig.builder()
            .defaultModelId("test-model-2")
            .maxIterations(10)
            .actionTimeoutMs(5000)
            .build())
        agent.withToolRegistry(mockToolRegistry)

        when:
        def result = agent.ask("Read x.txt and y.txt, write z.txt")

        then:
        result == "[FINISH]"
        // 验证 read tool 调用 2 次，write tool 调用 1 次
        readCalls.size() >= 2
        readCalls[0] == "read:x.txt"
        readCalls[1] == "read:y.txt"
        writeCalls.size() >= 1
        writeCalls[0] == "write:z.txt=data"
    }

    /**
     * 测试 __action_log 在 micro loop 中被正确累积。
     */
    def "should accumulate action log across micro loop iterations"() {
        given:
        def toolCalls1 = [
            new Call.ToolCall("mock_read", '{"path": "test.txt"}')
        ]
        def response1 = new Call.Response(
            "Read the file first.",
            new Call.TokenUsage(100, 20, 120),
            ["raw": toRawMetadata("mock_read", '{"path": "test.txt"}')],
            toolCalls1
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
                .modelId("test-model-3")
                .supplierName(mockSupplier.name())
                .capability(Model.Capability.FUNCTION_CALL)
                .pricing(new Model.Pricing(0, 0))
                .build())

        mockToolRegistry = createMockToolRegistry("mock_read", new MockToolBean(), ["path"] as String[])

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
    // Helpers
    // ========================================================================

    /**
     * 创建一个 ToolRegistry 并注册一个 mock tool。
     * mockOp 方法接收单个 String 参数（参数名来自第一个 property）。
     */
    private ToolRegistry createMockToolRegistry(String toolName, MockToolBean bean, String[] paramNames) {
        def tr = new ToolRegistry()
        def lookup = MethodHandles.lookup()
        // 注册单个 String 参数的方法：mockOp(String)
        tr.register(ToolMetadata.builder()
            .name(toolName)
            .description("Mock tool: $toolName")
            .inputSchema(createJsonSchema(paramNames))
            .targetMethod(lookup.findVirtual(MockToolBean, "mockOp",
                MethodType.methodType(String, String)))
            .targetBean(bean)
            .paramNames(paramNames)
            .build())
        return tr
    }

    /**
     * 创建简单的 JSON schema。
     */
    private static com.fasterxml.jackson.databind.JsonNode createJsonSchema(String[] properties) {
        def mapper = new com.fasterxml.jackson.databind.ObjectMapper()
        def root = mapper.createObjectNode()
        root.put("type", "object")
        def props = root.putObject("properties")
        for (p in properties) {
            def pn = props.putObject(p)
            pn.put("type", "string")
        }
        def arr = root.putArray("required")
        for (p in properties) {
            arr.add(p)
        }
        return root
    }

    /**
     * 构造 OpenAI 风格的 raw metadata 字符串，包含多个 tool_calls。
     * @param toolNamesAndArgs 交替传入工具名和参数JSON：name1, args1, name2, args2, ...
     */
    private static String toRawMetadata(Object... toolNamesAndArgs) {
        def sb = new StringBuilder()
        sb.append('{"id":"test","object":"chat.completion","created":1000000,"model":"test",')
        sb.append('"choices":[{"index":0,"message":{"role":"assistant","content":"ok","tool_calls":[')
        for (int i = 0; i < toolNamesAndArgs.length; i += 2) {
            if (i > 0) sb.append(',')
            def name = toolNamesAndArgs[i] as String
            def args = toolNamesAndArgs[i + 1] as String
            sb.append('{"index":').append(i / 2)
            sb.append(',"id":"call_00_').append(i / 2).append('",')
            sb.append('"type":"function",')
            sb.append('"function":{"name":"').append(name).append('","arguments":').append(args).append('}}')
        }
        sb.append(']}}')
        sb.append(',"logprobs":null,"finish_reason":"tool_calls"}],')
        sb.append('"usage":{"prompt_tokens":100,"completion_tokens":50,"total_tokens":150}}')
        return sb.toString()
    }
}

/**
 * 模拟工具 Bean：通过 MethodHandle 反射调用的真实 Java 方法。
 * 子测试类可以通过设置 mockOpImpl 来注入模拟行为。
 */
class MockToolBean {
    /**
     * 可替换的 Closure（单参版本）。
     * 签名：{ String msg -> "result" }
     */
    Closure mockOpImpl = { String msg -> "ok" }

    /**
     * 可替换的 Closure（双参版本）。
     * 签名：{ String path, String content -> "result" }
     */
    Closure mockOpImpl2 = { String path, String content -> "ok" }

    /**
     * 单字符串参数方法（ToolMetadata 按参数名提取后调用）。
     * @param msg 参数值
     * @return 结果字符串
     */
    String mockOp(String msg) {
        return mockOpImpl.call(msg)
    }

    /**
     * 双字符串参数方法（ToolMetadata 按参数名提取后调用）。
     */
    String mockOp2(String path, String content) {
        return mockOpImpl2.call(path, content)
    }
}

/**
 * 第二个模拟工具 Bean：接受 Map 参数（适合多参数场景）。
 */

