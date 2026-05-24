package org.cland.alice.agent.command

import spock.lang.Specification
import spock.lang.Title

/**
 * 测试 {@link CapabilityCmd} 及其子类型：
 * {@link CapabilityCmd.RegisterSkillCmd},
 * {@link CapabilityCmd.UpdateRulesCmd},
 * {@link CapabilityCmd.ReloadKernelCmd}.
 */
@Title("CapabilityCmd 密封接口")
class CapabilityCmdSpec extends Specification {

    static final String SESSION = "sess-01"
    static final String TRACE  = "trace-xyz"

    // ========================================================================
    // RegisterSkillCmd (/skill)
    // ========================================================================

    def "RegisterSkillCmd 应记录 skillRef, sessionId, traceId"() {
        given:
        def cmd = new CapabilityCmd.RegisterSkillCmd("mcp-tools.json", SESSION, TRACE)

        expect:
        cmd.skillRef()  == "mcp-tools.json"
        cmd.resource()  == "mcp-tools.json"
        cmd.sessionId() == SESSION
        cmd.traceId()   == TRACE
        cmd.timestamp() != null
    }

    def "RegisterSkillCmd 拒绝 null skillRef"() {
        when:
        new CapabilityCmd.RegisterSkillCmd(null, SESSION, TRACE)

        then:
        thrown(NullPointerException)
    }

    def "RegisterSkillCmd 拒绝 null sessionId"() {
        when:
        new CapabilityCmd.RegisterSkillCmd("ref", null, TRACE)

        then:
        thrown(NullPointerException)
    }

    // ========================================================================
    // UpdateRulesCmd (/rules)
    // ========================================================================

    def "UpdateRulesCmd 应记录 rulesRef, sessionId, traceId"() {
        given:
        def cmd = new CapabilityCmd.UpdateRulesCmd("rules/alice.prompt", SESSION, TRACE)

        expect:
        cmd.rulesRef()  == "rules/alice.prompt"
        cmd.resource()  == "rules/alice.prompt"
        cmd.sessionId() == SESSION
        cmd.traceId()   == TRACE
    }

    def "UpdateRulesCmd 拒绝 null rulesRef"() {
        when:
        new CapabilityCmd.UpdateRulesCmd(null, SESSION, TRACE)

        then:
        thrown(NullPointerException)
    }

    // ========================================================================
    // ReloadKernelCmd (/reload)
    // ========================================================================

    def "ReloadKernelCmd 应记录 sessionId, traceId, resource 应为星号"() {
        given:
        def cmd = new CapabilityCmd.ReloadKernelCmd(SESSION, TRACE)

        expect:
        cmd.resource()  == "*"
        cmd.sessionId() == SESSION
        cmd.traceId()   == TRACE
    }

    // ========================================================================
    // Record 相等性
    // ========================================================================

    def "相同字段的两个 RegisterSkillCmd 应相等"() {
        given:
        def ts = java.time.Instant.now()
        def a = new CapabilityCmd.RegisterSkillCmd("tool", SESSION, TRACE, ts)
        def b = new CapabilityCmd.RegisterSkillCmd("tool", SESSION, TRACE, ts)

        expect:
        a == b
        a.hashCode() == b.hashCode()
    }

    def "不同 resource 的 RegisterSkillCmd 不应相等"() {
        expect:
        new CapabilityCmd.RegisterSkillCmd("a", SESSION, TRACE) !=
        new CapabilityCmd.RegisterSkillCmd("b", SESSION, TRACE)
    }
}
