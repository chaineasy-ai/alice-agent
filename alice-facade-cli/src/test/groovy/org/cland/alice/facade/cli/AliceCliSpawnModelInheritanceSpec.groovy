/*
 * #747 — CLI spawn 必须继承父会话的**有效模型**（#666 D5 遗留的半生效口子）。
 *
 * 判据（票内定义，逐条对应）：
 *   ① 父切 x ⇒ CLI spawn 出的子 Agent 实际请求 modelId == x
 *   ② 无注入时行为不变（回落 baseAgentConfig.defaultModelId()）
 *   ③ 端到端：用假 provider 捕获"真正发给模型的 modelId"
 *   ④ 变异：去掉注入点 ⇒ 红点必须是 CLI spawn 那条
 */
package org.cland.alice.facade.cli

import org.cland.alice.agent.proto.AlignmentCmd
import org.cland.alice.agent.proto.SpawnSubAgentCmd
import org.cland.alice.agent.subagent.SubAgentRunner
import org.cland.alice.core.agent.Agent
import org.cland.alice.core.agent.AgentConfig
import org.cland.alice.model.Call
import org.cland.alice.model.Model
import org.cland.alice.model.ModelProvider
import org.cland.alice.model.ModelSupplier
import spock.lang.Specification
import spock.lang.Title

@Title("CLI spawn 继承父会话有效模型（#747）")
class AliceCliSpawnModelInheritanceSpec extends Specification {

    static final String SESSION = "sess-747"
    static final String TRACE = "trace-747"
    /** 一个真实注册到 ModelProvider 的"可切换目标"模型（能力与默认模型一致，避免跨能力拒绝）。 */
    static final String FAKE_MODEL = "fake-switch-target-747"

    def cleanup() {
        AliceCliLauncher.testSubAgentRunner = null
    }

    private static void registerFakeModel() {
        def provider = ModelProvider.getInstance()
        provider.registerBuiltinModels()
        if (provider.getModel(FAKE_MODEL) == null) {
            provider.registerModel(
                Model.builder()
                    .modelId(FAKE_MODEL)
                    .supplierName("fake747")
                    .capability(Model.Capability.ALL)
                    .pricing(new Model.Pricing(0.0d, 0.0d))
                    .build())
        }
        provider.registerSupplier(new ModelSupplier() {
            @Override
            String name() { "fake747" }
            @Override
            Call.Response request(Call call) {
                return Call.Response.textOnly("fake-response", new Call.TokenUsage(1, 1, 2), [:])
            }
        })
    }

    /** 捕获 spawn 传给 runner 的 AgentConfig（= 子 Agent 的生效模型来源）。 */
    private static List<AgentConfig> captureSpawnConfigs() {
        def captured = []
        AliceCliLauncher.testSubAgentRunner = { String id, String goal, AgentConfig cfg ->
            captured << cfg
            return "done"
        } as SubAgentRunner
        return captured
    }

    def "① 父切 x ⇒ CLI spawn 的子 Agent 收到 x（不是默认值）"() {
        given:
        registerFakeModel()
        def parent = new Agent()
        def captured = captureSpawnConfigs()

        and: "通过 CLI 的 SwitchModelCmd 真的把父会话切到 x（#747 同时修了 CLI 切换分支只打印不生效）"
        def switchRc = AliceCliLauncher.dispatchCommand(
            new AlignmentCmd.SwitchModelCmd(FAKE_MODEL, SESSION, TRACE), parent)

        expect:
        switchRc == AliceCliLauncher.EXIT_SUCCESS
        parent.currentModelId() == FAKE_MODEL

        when: "CLI spawn 一个不带显式模型的子 Agent"
        def spawnRc = AliceCliLauncher.dispatchCommand(
            new SpawnSubAgentCmd("child goal", null, SESSION, TRACE), parent)
        long deadline = System.currentTimeMillis() + 5_000
        while (captured.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }

        then:
        spawnRc == AliceCliLauncher.EXIT_SUCCESS
        captured.size() == 1
        captured[0].defaultModelId() == FAKE_MODEL
        captured[0].defaultModelId() != AgentConfig.defaults().defaultModelId()
    }

    def "② 无父会话（parentAgent == null）时行为与历史一致：回落默认模型"() {
        given:
        registerFakeModel()
        def captured = captureSpawnConfigs()
        def defaultModel = AgentConfig.defaults().defaultModelId()

        when: "一次性命令（没有会话上下文）spawn 子 Agent"
        AliceCliLauncher.dispatchCommand(
            new SpawnSubAgentCmd("one-shot goal", null, SESSION, TRACE))
        long deadline = System.currentTimeMillis() + 5_000
        while (captured.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }

        then: "不注入 ⇒ 仍用 baseAgentConfig 默认值（P-DEFAULT：默认行为不许被顺手改掉）"
        captured.size() == 1
        captured[0].defaultModelId() == defaultModel
    }

    def "③ 端到端：子 Agent 真正发给 provider 的 modelId 就是继承来的 x"() {
        given: "假 provider 捕获真实请求"
        registerFakeModel()
        def requested = []
        ModelProvider.getInstance().registerSupplier(new ModelSupplier() {
            @Override
            String name() { "fake747" }
            @Override
            Call.Response request(Call call) {
                requested << call.payload().modelId()
                return Call.Response.textOnly("pong", new Call.TokenUsage(1, 1, 2), [:])
            }
        })
        def parent = new Agent()
        def captured = captureSpawnConfigs()
        AliceCliLauncher.dispatchCommand(
            new AlignmentCmd.SwitchModelCmd(FAKE_MODEL, SESSION, TRACE), parent)
        AliceCliLauncher.dispatchCommand(new SpawnSubAgentCmd("child goal", null, SESSION, TRACE), parent)
        long deadline = System.currentTimeMillis() + 5_000
        while (captured.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }

        when: "用子 Agent 收到的配置真实跑一次推理"
        def child = new Agent(captured[0])
        def answer = child.ask("ping")

        then: "发给模型的 modelId 必须是 x（不是默认值）—— 这才是实际请求的口径"
        answer == "pong"
        !requested.isEmpty()
        requested.every { it == FAKE_MODEL }
    }
}
