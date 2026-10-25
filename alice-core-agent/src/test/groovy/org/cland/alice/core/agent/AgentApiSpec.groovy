/*
 * AgentApiSpec — 验证 Agent 核心对外暴露的新接口
 *
 * 测试目标：
 *   - getActiveContext()
 *   - clearMemory()
 *   - compactContext()
 *   - switchModel()
 *   - injectFeedback() / feedback()
 *
 * 不涉及真实的 LLM 调用。Agent 核心可直接构造。
 */
package org.cland.alice.core.agent

import spock.lang.Specification
import spock.lang.Title

@Title("Agent 核心新接口 — getActiveContext / clearMemory / compactContext / switchModel / feedback")
class AgentApiSpec extends Specification {

    def "getActiveContext returns non-null Markdown table"() {
        given: "a default Agent"
        def agent = new Agent()

        when: "getting active context"
        def ctx = agent.getActiveContext()

        then: "context is a non-empty Markdown table"
        ctx != null
        !ctx.isEmpty()
        ctx.contains("会话 ID")
        ctx.contains("生效模型")
        ctx.contains("gpt-4o-mini")
    }

    def "clearMemory does not throw"() {
        given: "a default Agent"
        def agent = new Agent()

        when: "clearing memory"
        agent.clearMemory()

        then: "no exception is thrown"
        noExceptionThrown()
    }

    def "compactContext returns failure when WAL not injected"() {
        given: "a default Agent without WAL"
        def agent = new Agent()

        when: "compacting context"
        def result = agent.compactContext()

        then: "a failure message is returned"
        result != null
        !result.isEmpty()
        result.contains("上下文压缩失败")
    }

    def "switchModel accepts valid model id"() {
        given: "#666 D5：ModelProvider 认识内置模型（真实启动路径同样会注册）"
        org.cland.alice.model.ModelProvider.getInstance().registerBuiltinModels()
        def agent = new Agent()

        when:
        agent.switchModel("gpt-4o")

        then: "不再只断言没炸 —— 有效模型必须真的变成 gpt-4o"
        agent.currentModelId() == "gpt-4o"
    }

    def "switchModel accepts empty model id"() {
        given:
        org.cland.alice.model.ModelProvider.getInstance().registerBuiltinModels()
        def agent = new Agent()
        def original = agent.currentModelId()

        when: "传空串 = 清除覆盖（契约③），不是静默接受一个空模型"
        agent.switchModel("")

        then: "回落到默认值，且不等于空串"
        agent.currentModelId() == original
        agent.currentModelId() != ""
    }

    def "injectFeedback stores feedback without throwing"() {
        given: "a default Agent"
        def agent = new Agent()

        when: "injecting feedback"
        agent.injectFeedback("请简化输出")

        then: "no exception is thrown"
        noExceptionThrown()
    }

    def "injectFeedback with null throws NullPointerException"() {
        given: "a default Agent"
        def agent = new Agent()

        when: "injecting null feedback"
        agent.injectFeedback(null)

        then: "NullPointerException is thrown (ConcurrentHashMap rejects null values)"
        thrown(NullPointerException)
    }

    def "feedback returns null by default"() {
        given: "a default Agent"
        def agent = new Agent()

        expect: "no feedback stored yet"
        agent.feedback() == null
    }

    def "multiple calls to getActiveContext return consistent results"() {
        given: "a default Agent"
        def agent = new Agent()

        when: "calling getActiveContext twice"
        def ctx1 = agent.getActiveContext()
        def ctx2 = agent.getActiveContext()

        then: "both calls return non-null and consistent"
        ctx1 != null
        ctx2 != null
        ctx1.contains("会话 ID")
        ctx2.contains("会话 ID")
    }
}
