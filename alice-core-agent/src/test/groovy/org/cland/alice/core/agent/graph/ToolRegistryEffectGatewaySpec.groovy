package org.cland.alice.core.agent.graph

import org.cland.alice.core.agent.Agent
import org.cland.alice.core.agent.AgentConfig
import org.cland.alice.core.agent.guardrail.GuardrailVerificatorAdapter
import org.cland.alice.core.agent.kernel.graph.ToolCallReq
import org.cland.alice.tool.gateway.engine.ExecutionEngine
import org.cland.alice.tool.gateway.engine.ToolResult
import spock.lang.Specification
import spock.lang.Title

/**
 * #665 graph-kernel 护栏 — 效果网关的 pre-verify 钩子。
 *
 * <p>图内核的副作用走 {@link ToolRegistryEffectGateway} → {@code ExecutionEngine}，
 * 不经过 {@code AgentExecutor}，因此必须由钩子把 {@code Agent.verifyToolCall} 的判据接进效果边界。
 *
 * <p>关键断言不是"返回失败"，而是 <b>内层 engine.invoke 的调用次数</b>：
 * 拦截 ⇒ 0 次（副作用根本没发生）；放行 ⇒ 1 次。
 */
@Title("#665 graph-kernel effect gateway pre-verify hook")
class ToolRegistryEffectGatewaySpec extends Specification {

    private static ToolRegistryEffectGateway.PreVerifyHook alwaysBlock(String reason = "Blocked by test hook") {
        { String tool, Map args -> reason } as ToolRegistryEffectGateway.PreVerifyHook
    }

    private static ToolRegistryEffectGateway.PreVerifyHook alwaysAllow() {
        { String tool, Map args -> null } as ToolRegistryEffectGateway.PreVerifyHook
    }

    def "blocking hook returns FAILURE without ever invoking the inner engine"() {
        given: "永远拦截的钩子 + spy 执行引擎"
        def engine = Mock(ExecutionEngine)
        def gateway = new ToolRegistryEffectGateway(engine, alwaysBlock("blocked by test hook"))

        when:
        def outcome = gateway.invoke(
            ToolCallReq.of("run", [command: "dd if=/dev/zero of=/tmp/x bs=1 count=1"]))

        then: "engine.invoke 零调用 —— 副作用没有发生"
        0 * engine.invoke(_, _)

        and: "返回失败 observation，携带拦截原因（legacy blocked 语义）"
        !outcome.success()
        outcome.observation() == "blocked by test hook"
    }

    def "allowing hook invokes the inner engine exactly once"() {
        given:
        def engine = Mock(ExecutionEngine)
        def gateway = new ToolRegistryEffectGateway(engine, alwaysAllow())

        when:
        def outcome = gateway.invoke(ToolCallReq.of("run", [command: "ls -l /tmp"]))

        then: "放行 ⇒ 引擎恰好执行 1 次"
        1 * engine.invoke("run", [command: "ls -l /tmp"]) >> ToolResult.success("total 0")
        outcome.success()
        outcome.observation() == "total 0"
    }

    def "real Agent.verifyToolCall blocks dd through the hook (integration)"() {
        given: "真实 guardrail 链路（Agent.verifyToolCall → verifyPre → GuardrailVerificatorAdapter）"
        def agent = Agent.createDefault(AgentConfig.builder().build())
            .withGuardrail(new GuardrailVerificatorAdapter(false))
        def engine = Mock(ExecutionEngine)
        def gateway = new ToolRegistryEffectGateway(
            engine, { String tool, Map args -> agent.verifyToolCall(tool, args) }
                as ToolRegistryEffectGateway.PreVerifyHook)

        when: "危险命令藏在工具参数里"
        def outcome = gateway.invoke(
            ToolCallReq.of("run", [command: "dd if=/dev/zero of=/tmp/x bs=1 count=1"]))

        then: "引擎零调用，且 observation 含拦截原因"
        0 * engine.invoke(_, _)
        !outcome.success()
        outcome.observation().startsWith("Blocked by guardrail")
    }

    def "real Agent.verifyToolCall allows a benign command through the hook (positive control)"() {
        given:
        def agent = Agent.createDefault(AgentConfig.builder().build())
            .withGuardrail(new GuardrailVerificatorAdapter(false))
        def engine = Mock(ExecutionEngine)
        def gateway = new ToolRegistryEffectGateway(
            engine, { String tool, Map args -> agent.verifyToolCall(tool, args) }
                as ToolRegistryEffectGateway.PreVerifyHook)

        when:
        def outcome = gateway.invoke(ToolCallReq.of("run", [command: "ls -l /tmp"]))

        then: "正控：正常命令放行，引擎执行 1 次"
        1 * engine.invoke("run", [command: "ls -l /tmp"]) >> ToolResult.success("total 0")
        outcome.success()
    }
}
