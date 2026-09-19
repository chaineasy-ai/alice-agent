package org.cland.alice.runtime.subagent

import org.cland.alice.core.agent.AgentConfig
import org.cland.alice.runtime.FakeEngine
import org.cland.alice.runtime.compose.AgentComposer
import org.cland.alice.runtime.compose.StartupBanner
import spock.lang.Specification
import spock.lang.Title

import java.util.function.Function

/**
 * B5：契约子 Agent 执行器 —— 经 AgentHost + InProcessTransport 跑完一轮并回传文本。
 *
 * 注入假组合根（假引擎）⇒ 不触网、确定性。
 */
@Title("ContractSubAgentRunner — 契约执行")
class ContractSubAgentRunnerSpec extends Specification {

    static final String SUB = "sub-1"

    def "一轮执行后回传最后一个 SUMMARY 文本"() {
        given:
        def engine = new FakeEngine()
        engine.session = SUB
        engine.askBody = { p -> engine.fireThought("想一下"); return "sub-answer" }
        def runner = new ContractSubAgentRunner({ opts -> composed(engine) } as Function, 5000)

        when:
        def text = runner.run(SUB, "do it", config("test-model"))

        then:
        text == "sub-answer"
        engine.prompts == ["do it"]
    }

    def "ERROR 帧 ⇒ 抛异常（由 SubAgentManager 记 FAILED）"() {
        given:
        def engine = new FakeEngine()
        engine.session = SUB
        engine.askBody = { p -> throw new IllegalStateException("子 Agent 炸了") }
        def runner = new ContractSubAgentRunner({ opts -> composed(engine) } as Function, 5000)

        when:
        runner.run(SUB, "do it", config("test-model"))

        then:
        def e = thrown(IllegalStateException)
        e.message.contains("子 Agent 执行失败")
        e.message.contains("子 Agent 炸了")
    }

    def "组合根选项：子 Agent 会话名/模型透传、不落 WAL"() {
        given:
        def captured = []
        def engine = new FakeEngine()
        engine.session = SUB
        def runner = new ContractSubAgentRunner({ opts ->
            captured << opts
            composed(engine)
        } as Function, 5000)

        when:
        runner.run(SUB, "goal", config("my-model"))

        then:
        captured.size() == 1
        captured[0].sessionId() == SUB
        captured[0].model() == "my-model"
        !captured[0].wal()
        captured[0].transports() == ["inprocess"]
    }

    // ── helpers ──

    static AgentConfig config(String model) {
        return AgentConfig.builder().defaultModelId(model).build()
    }

    static AgentComposer.Composed composed(Object engine) {
        def banner = new StartupBanner.Data("ctx", ["t"], ["p"], ["e"], "主 Agent a", "rt", "loop")
        return new AgentComposer.Composed(engine as org.cland.alice.runtime.engine.AgentEngine, SUB, banner)
    }
}
