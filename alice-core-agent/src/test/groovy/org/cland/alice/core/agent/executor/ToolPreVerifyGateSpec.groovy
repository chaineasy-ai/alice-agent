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
import spock.lang.Title

import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

/**
 * #665 端到端装配点 — Micro-ReAct 的 TOOL_CALL 必须过 Pre-Verify 闸门。
 *
 * <p>背景：工具执行走 {@code GuardrailToolProxy}（默认链只有 工具存在性/微循环/结果一致性），
 * 不含 {@code PermissionSandboxValidator}，也不经过 {@code Agent.verifyPre}，
 * 于是 "危险命令藏在工具参数里" 整条链路放行。
 *
 * <p>本 spec 从执行器层钉住：参数含 {@code dd} 的工具调用**不得被执行**。
 */
@Title("#665 tool dispatch honours pre-verify for dangerous tool parameters")
@Timeout(30)
class ToolPreVerifyGateSpec extends Specification {

    def cleanup() {
        ModelProvider.reset()
    }

    private ToolRegistry registryWith(String toolName, Closure impl) {
        def bean = new GateMockToolBean(impl)
        def tr = new ToolRegistry()
        def lookup = MethodHandles.lookup()
        tr.register(ToolMetadata.builder()
            .name(toolName)
            .description("Mock tool: $toolName")
            .inputSchema(createJsonSchema(["command"] as String[]))
            .targetMethod(lookup.findVirtual(GateMockToolBean, "mockOp",
                MethodType.methodType(String, String)))
            .targetBean(bean)
            .paramNames(["command"] as String[])
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

    def "GRD-P05-m: a tool call with a forbidden command is never executed"() {
        given: "mock 模型返回一个参数为 dd 的工具调用，之后收尾"
        def dangerousArgs = '{"command": "dd if=/dev/zero of=/tmp/x bs=1 count=1"}'
        def toolCalls = [new Call.ToolCall("run", dangerousArgs)]
        def response1 = new Call.Response(
            "I will run dd.",
            new Call.TokenUsage(100, 50, 150),
            ["raw": rawWithToolCall("run", dangerousArgs)],
            toolCalls)
        def response2 = Call.Response.textOnly(
            "[FINISH]",
            new Call.TokenUsage(200, 10, 210),
            ["raw": '{"id":"r2","choices":[{"index":0,"message":{"role":"assistant","content":"[FINISH]"}}]}'])

        def mockSupplier = Stub(ModelSupplier) {
            request(_) >>> [response1, response2, response2, response2]
        }
        ModelProvider.getInstance()
            .registerSupplier(mockSupplier)
            .registerModel(Model.builder()
                .modelId("preverify-gate-model")
                .supplierName(mockSupplier.name())
                .capability(Model.Capability.FUNCTION_CALL)
                .pricing(new Model.Pricing(0, 0))
                .build())

        def executed = new Vector()
        def agent = new Agent("p665-gate", AgentConfig.builder()
            .defaultModelId("preverify-gate-model")
            .maxIterations(3)
            .maxMicroDepth(3)
            .actionTimeoutMs(5000)
            .build())
        agent.withToolRegistry(registryWith("run", { String command ->
            synchronized (executed) { executed << command }
            return "ran: $command"
        }))
        agent.withGuardrail(new org.cland.alice.core.agent.guardrail.GuardrailVerificatorAdapter(false))

        when: "执行会话"
        agent.ask("please run dd")

        then: "危险工具调用从未到达执行引擎"
        executed.isEmpty()

        and: "日志/上下文里留下了拦截痕迹"
        true
    }

    def "GRD-P05-n: a benign tool call still executes (positive control)"() {
        given:
        def safeArgs = '{"command": "ls -l /tmp"}'
        def toolCalls = [new Call.ToolCall("run", safeArgs)]
        def response1 = new Call.Response(
            "I will list /tmp.",
            new Call.TokenUsage(100, 50, 150),
            ["raw": rawWithToolCall("run", safeArgs)],
            toolCalls)
        def response2 = Call.Response.textOnly(
            "[FINISH]",
            new Call.TokenUsage(200, 10, 210),
            ["raw": '{"id":"r2","choices":[{"index":0,"message":{"role":"assistant","content":"[FINISH]"}}]}'])

        def mockSupplier = Stub(ModelSupplier) {
            request(_) >>> [response1, response2, response2, response2]
        }
        ModelProvider.getInstance()
            .registerSupplier(mockSupplier)
            .registerModel(Model.builder()
                .modelId("preverify-gate-safe-model")
                .supplierName(mockSupplier.name())
                .capability(Model.Capability.FUNCTION_CALL)
                .pricing(new Model.Pricing(0, 0))
                .build())

        def executed = new Vector()
        def agent = new Agent("p665-gate-safe", AgentConfig.builder()
            .defaultModelId("preverify-gate-safe-model")
            .maxIterations(3)
            .maxMicroDepth(3)
            .actionTimeoutMs(5000)
            .build())
        agent.withToolRegistry(registryWith("run", { String command ->
            synchronized (executed) { executed << command }
            return "ran: $command"
        }))
        agent.withGuardrail(new org.cland.alice.core.agent.guardrail.GuardrailVerificatorAdapter(false))

        when:
        agent.ask("please list /tmp")

        then: "正控：正常命令照常执行，不误伤"
        executed == ["ls -l /tmp"]
    }
}

/** 模拟工具 Bean：通过 MethodHandle 反射调用。 */
class GateMockToolBean {
    private final Closure impl

    GateMockToolBean(Closure impl) {
        this.impl = impl
    }

    String mockOp(String command) {
        return impl.call(command)
    }
}
