package org.cland.alice.runtime.compose

import spock.lang.Specification
import spock.lang.Title

/**
 * 测试组合根 {@link AgentComposer} 与统一 ID {@link Ids}。
 *
 * 不触网：只验证装配可用与标识口径（模型用测试 ID）。
 */
@Title("AgentComposer — 组合根")
class AgentComposerSpec extends Specification {

    def "compose：显式会话/模型、不挂 WAL/Guardrail ⇒ 引擎可用且会话一致"() {
        when:
        def composed = AgentComposer.compose(new AgentComposer.Options("sess-it", "test-model", false, false))

        then:
        composed.sessionId() == "sess-it"
        composed.engine() != null
        composed.engine().sessionId() == "sess-it"
        composed.engine().events() != null
    }

    def "compose：缺省会话/模型 ⇒ 自动生成，不回 null"() {
        when:
        def composed = AgentComposer.compose(new AgentComposer.Options(null, null, false, false))

        then:
        composed.sessionId() != null
        !composed.sessionId().isBlank()
        composed.engine().sessionId() == composed.sessionId()
    }

    def "compose：null 选项 ⇒ 取 defaults（可用即可）"() {
        when:
        def composed = AgentComposer.compose(null)

        then:
        composed.engine() != null
        !composed.sessionId().isBlank()
    }

    def "Ids：traceId 12 位且会话 ID 互异"() {
        when:
        def t1 = Ids.newTraceId()
        def t2 = Ids.newTraceId()
        def s1 = Ids.newSessionId()
        def s2 = Ids.newSessionId()

        then:
        t1.length() == 12
        t2.length() == 12
        t1 != t2
        !s1.isBlank() && !s2.isBlank()
        s1 != s2
    }
}
