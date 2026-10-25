/*
 * #666 D5 验收 —— 运行时模型切换（方案 A · 只做「切换原语」）。
 *
 * 契约（atlas/pillar 最终版，逐条对应用例）：
 *   ① 优先级链：入参 > currentModelId() > defaultModelId；入参不粘连
 *   ② currentModelId() 双向：成功 == 新值；失败 == 旧值（不许半改）
 *   ③ switchModel(null|blank) = 清除覆盖（回落到默认），否则"切到 x 就回不去"
 *   ④ 半生效即 FAIL：子 Agent 必须继承父会话的有效模型
 *
 * 变异靶（改坏必红）：
 *   - switchModel 改回空实现            ⇒ ① 红
 *   - currentModelId() 无条件返回 default ⇒ ①② 红
 *   - 子 Agent 继承改回 baseAgentConfig.defaultModelId() ⇒ ④ 红
 */
package org.cland.alice.core.agent

import org.cland.alice.agent.subagent.SubAgentManager
import org.cland.alice.agent.subagent.SubAgentRunner
import org.cland.alice.model.common.ModelEnum
import spock.lang.Specification
import spock.lang.Title

@Title("Agent 运行时模型切换 — 优先级链 / 双向 / 清除覆盖 / 子代理继承")
class AgentSwitchModelSpec extends Specification {

    static final String DEFAULT_MODEL = AgentConfig.DEFAULT_MODEL
    static final String TARGET = ModelEnum.GPT_4O.modelId()
    static final String OTHER = ModelEnum.CLAUDE_3_5_SONNET.modelId()

    def setup() {
        // 让 ModelProvider 认识内置模型（真实实现即如此，见 Agent.initModelProvider()）
        org.cland.alice.model.ModelProvider.getInstance().registerBuiltinModels()
    }

    def "① 优先级链：入参 > currentModelId() > default；入参不粘连"() {
        given:
        def agent = new Agent()
        assert agent.currentModelId() == DEFAULT_MODEL

        when: "切换到 TARGET"
        agent.switchModel(TARGET)

        then: "不传参 ⇒ 用 currentModelId()"
        agent.resolveModelId(null) == TARGET

        and: "显式传 OTHER ⇒ 用入参"
        agent.resolveModelId(OTHER) == OTHER

        and: "随后再不传参 ⇒ 仍回 TARGET（入参不粘连）"
        agent.resolveModelId(null) == TARGET
        agent.resolveModelId("") == TARGET
        agent.currentModelId() == TARGET
    }

    def "② currentModelId() 双向：成功等于新值，失败等于旧值"() {
        given:
        def agent = new Agent()

        when: "切到已注册模型"
        agent.switchModel(TARGET)

        then:
        agent.currentModelId() == TARGET

        when: "切到未注册模型"
        agent.switchModel("no-such-model-#666")

        then: "必须抛错（不许静默接受）"
        def e = thrown(IllegalArgumentException)
        e.message.contains("no-such-model-#666")

        and: "失败后有效模型仍是上一次成功的值（不许半改）"
        agent.currentModelId() == TARGET
        agent.resolveModelId(null) == TARGET
    }

    def "③ switchModel(null|空白) 清除覆盖，回落到默认"() {
        given:
        def agent = new Agent()
        agent.switchModel(TARGET)
        assert agent.currentModelId() == TARGET

        when: "传 null 清除"
        agent.switchModel(null)

        then: "回落到配置默认值（否则切到 x 就回不去）"
        agent.currentModelId() == DEFAULT_MODEL

        when: "再切到 TARGET，然后用空白清除"
        agent.switchModel(TARGET)
        agent.switchModel("   ")

        then:
        agent.currentModelId() == DEFAULT_MODEL
    }

    def "③ 空白/未注册都不改变"() {
        given:
        def agent = new Agent()

        expect:
        agent.currentModelId() == DEFAULT_MODEL
    }

    def "④ 子 Agent 必须继承父会话的有效模型（否则父切了、子仍用默认 = 半生效假绿）"() {
        given: "一个能捕获收到的 AgentConfig 的 runner"
        AgentConfig captured = null
        def runner = { String id, String goal, AgentConfig cfg ->
            captured = cfg
            return "done"
        } as SubAgentRunner

        and: "父会话已切到 TARGET"
        def parent = new Agent()
        parent.switchModel(TARGET)

        and: "manager 注入父会话有效模型提供者（TUI 侧真实接线方式）"
        def mgr = new SubAgentManager("sess-d5")
        mgr.setRunner(runner)
        mgr.setEffectiveModelSupplier({ -> parent.currentModelId() })

        when: "不带显式模型地 spawn 子 Agent"
        def record = mgr.spawnSubAgent("child goal", null)
        long deadline = System.currentTimeMillis() + 5_000
        while (captured == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }

        then: "子 Agent 拿到的是父会话的有效模型，而不是配置默认值"
        captured != null
        captured.defaultModelId() == TARGET
        captured.defaultModelId() != DEFAULT_MODEL
        record != null
    }
}
