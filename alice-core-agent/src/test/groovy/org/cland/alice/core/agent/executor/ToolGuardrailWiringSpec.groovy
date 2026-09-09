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
 * P6 装配点验证 — 工具级守卫（GuardrailToolProxy）随执行器自动接线。
 *
 * 覆盖 kernel-architecture.md §7 迁移行"GuardrailToolProxy 未接线 → 由 Agent 装配注入（修 P6）"：
 * 执行器在首个工具调用前自动装配默认代理，工具流程经代理执行且不产生误伤。
 */
@Timeout(15)
class ToolGuardrailWiringSpec extends Specification {

    def cleanup() {
        ModelProvider.reset()
    }

    private ToolRegistry registryWith(String toolName, Closure impl) {
        def bean = new WiringMockToolBean(impl)
        def tr = new ToolRegistry()
        def lookup = MethodHandles.lookup()
        tr.register(ToolMetadata.builder()
            .name(toolName)
            .description("Mock tool: $toolName")
            .inputSchema(createJsonSchema(["msg"] as String[]))
            .targetMethod(lookup.findVirtual(WiringMockToolBean, "mockOp",
                MethodType.methodType(String, String)))
            .targetBean(bean)
            .paramNames(["msg"] as String[])
            .build())
        return tr
    }

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
        for (p in properties) { arr.add(p) }
        return root
    }

    private static String rawWithToolCall(String name, String args) {
        return '{"id":"test","object":"chat.completion","created":1000000,"model":"test",'
            + '"choices":[{"index":0,"message":{"role":"assistant","content":"ok","tool_calls":['
            + '{"index":0,"id":"call_0","type":"function",'
            + '"function":{"name":"' + name + '","arguments":' + args + '}}]}},'
            + '"logprobs":null,"finish_reason":"tool_calls"}],'
            + '"usage":{"prompt_tokens":100,"completion_tokens":50,"total_tokens":150}}'
    }

    def "tool calls run through the auto-wired GuardrailToolProxy"() {
        given: "mock model returns one tool call then a plain finish"
        def toolCalls = [new Call.ToolCall("mock_op", '{"msg": "hello"}')]
        def response1 = new Call.Response(
            "I will call the tool.",
            new Call.TokenUsage(100, 50, 150),
            ["raw": rawWithToolCall("mock_op", '{"msg": "hello"}')],
            toolCalls)
        def response2 = Call.Response.textOnly(
            "[FINISH]",
            new Call.TokenUsage(200, 10, 210),
            ["raw": '{"id":"r2","choices":[{"index":0,"message":{"role":"assistant","content":"[FINISH]"}}]}'])

        def mockSupplier = Stub(ModelSupplier) {
            request(_) >>> [response1, response2]
        }
        ModelProvider.getInstance()
            .registerSupplier(mockSupplier)
            .registerModel(Model.builder()
                .modelId("guardrail-test-model")
                .supplierName(mockSupplier.name())
                .capability(Model.Capability.FUNCTION_CALL)
                .pricing(new Model.Pricing(0, 0))
                .build())

        def calls = new Vector()
        def agent = new Agent("p6-wiring", AgentConfig.builder()
            .defaultModelId("guardrail-test-model")
            .maxIterations(10)
            .actionTimeoutMs(5000)
            .build())
        agent.withToolRegistry(registryWith("mock_op", { String msg ->
            synchronized (calls) { calls << msg }
            return "done: $msg"
        }))

        when: "running a session that executes one tool call"
        def result = agent.ask("call the tool")

        then: "tool executed through the guardrail proxy without false positive"
        result == "[FINISH]"
        calls == ["hello"]

        and: "the default GuardrailToolProxy was auto-wired (P6)"
        agent.kernel() instanceof AgentExecutor
        ((AgentExecutor) agent.kernel()).isGuardrailToolProxyEnabled()
    }
}

/** 模拟工具 Bean：通过 MethodHandle 反射调用的真实 Java 方法。 */
class WiringMockToolBean {
    private final Closure impl

    WiringMockToolBean(Closure impl) {
        this.impl = impl
    }

    String mockOp(String msg) {
        return impl.call(msg)
    }
}
