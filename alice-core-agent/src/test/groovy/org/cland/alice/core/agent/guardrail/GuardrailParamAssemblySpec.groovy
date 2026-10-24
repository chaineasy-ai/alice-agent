package org.cland.alice.core.agent.guardrail

import org.cland.alice.core.agent.Agent
import org.cland.alice.core.agent.AgentConfig
import org.cland.alice.core.agent.lifecycle.Action
import spock.lang.Specification
import spock.lang.Timeout
import spock.lang.Title

/**
 * #665 装配层参数透传 — 洞测试。
 *
 * <p>上一段（acfa1ef）已在 {@code PermissionSandboxValidator} 增加参数递归扫描，
 * 但装配层没把参数送给 validator，端到端仍然放行。本 spec 从上往下钉住两处装配点：
 *
 * <ol>
 *   <li>{@code Agent.verifyPre(Action)} —— Action.parameters() 必须进入 action map；
 *   <li>{@code GuardrailVerificatorAdapter.intercept(Map)} —— parameters 必须进入 Plan.Step。
 * </ol>
 *
 * <p>黑名单类：{@code dd}（精确命令名）、{@code /etc/}（系统路径前缀）。
 */
@Title("#665 assembly layer forwards tool parameters to the permission sandbox validator")
@Timeout(30)
class GuardrailParamAssemblySpec extends Specification {

    def adapter = new GuardrailVerificatorAdapter(false)

    // ── GRD-P05-e: GuardrailVerificatorAdapter.intercept 装配层 ──────

    def "GRD-P05-e: intercept REJECTs a dangerous command carried in parameters"() {
        given: "装配层 action map，target 只是工具名，危险命令在 parameters 里"
        def action = Map.of(
            "type", "TOOL_CALL",
            "target", "run",
            "actionId", "t1",
            "parameters", Map.of("command", "dd if=/dev/zero of=/tmp/x bs=1 count=1"))

        when:
        def allowed = adapter.intercept(action)

        then: "必须被拦截"
        !allowed
    }

    def "GRD-P05-f: intercept ALLOWs a benign command in parameters (positive control)"() {
        given:
        def action = Map.of(
            "type", "TOOL_CALL",
            "target", "run",
            "actionId", "t2",
            "parameters", Map.of("command", "ls -l /tmp"))

        when:
        def allowed = adapter.intercept(action)

        then: "正控：不能误伤"
        allowed
    }

    def "GRD-P05-g: intercept still REJECTs a restricted path in parameters"() {
        given:
        def action = Map.of(
            "type", "TOOL_CALL",
            "target", "read_file",
            "actionId", "t3",
            "parameters", Map.of("path", "/etc/passwd"))

        when:
        def allowed = adapter.intercept(action)

        then:
        !allowed
    }

    def "GRD-P05-h: intercept tolerates missing parameters (backward compatible)"() {
        given: "老调用方不传 parameters"
        def action = Map.of("type", "TOOL_CALL", "target", "run", "actionId", "t4")

        when:
        def allowed = adapter.intercept(action)

        then: "按空 Map 处理，不抛异常、不改变 target 语义"
        allowed
    }

    def "GRD-P05-i: intercept ignores non-map parameters instead of throwing"() {
        given: "类型不符（String 而非 Map）"
        def action = Map.of(
            "type", "TOOL_CALL",
            "target", "run",
            "actionId", "t5",
            "parameters", "dd if=/dev/zero of=/tmp/x")

        when:
        def allowed = adapter.intercept(action)

        then: "按空 Map 处理，不抛异常"
        allowed
    }

    // ── GRD-P05-j: Agent.verifyPre 装配层 ───────────────────────────

    def "GRD-P05-j: Agent.verifyPre REJECTs a dangerous command in Action.parameters()"() {
        given: "legacy 链路：guardrail 就是 GuardrailVerificatorAdapter"
        def agent = Agent.createDefault(AgentConfig.builder().build())
            .withGuardrail(new GuardrailVerificatorAdapter(false))

        expect: "危险命令必须被拦"
        !agent.verifyPre(Action.toolCall("run", Map.of("command", "dd if=/dev/zero of=/tmp/x bs=1 count=1")))
    }

    def "GRD-P05-k: Agent.verifyPre ALLOWs a benign tool call (positive control)"() {
        given:
        def agent = Agent.createDefault(AgentConfig.builder().build())
            .withGuardrail(new GuardrailVerificatorAdapter(false))

        expect: "正控：正常命令不被误伤"
        agent.verifyPre(Action.toolCall("run", Map.of("command", "ls -l /tmp")))
    }

    def "GRD-P05-l: Agent.verifyPre handles a parameter-less action"() {
        given:
        def agent = Agent.createDefault(AgentConfig.builder().build())
            .withGuardrail(new GuardrailVerificatorAdapter(false))

        expect: "无参数 Action 不因 Map.of(null) 抛异常"
        agent.verifyPre(Action.finish())
    }
}
