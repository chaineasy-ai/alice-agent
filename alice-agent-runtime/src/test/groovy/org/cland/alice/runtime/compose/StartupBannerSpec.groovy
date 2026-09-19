package org.cland.alice.runtime.compose

import spock.lang.Specification
import spock.lang.Title

/**
 * 测试启动横幅：七块齐全（Context/Skills/Prompts/Extensions + agents/runtime/loop），
 * 且采集端对缺失来源降级不抛错。
 */
@Title("StartupBanner — 启动横幅")
class StartupBannerSpec extends Specification {

    def "render：七块齐全、内容可见"() {
        given:
        def data = new StartupBanner.Data(
                "AGENTS.md ✓ · rules 2 · prompts 3",
                ["read_file", "write_file", "grep"],
                ["sales", "support"],
                ["第一方：tool-gateway · memory-vault · guardrail · env-adapter（MCP/ACP）"],
                "主 Agent a-1（子 Agent 0）",
                "session=s-1 · JVM=25 · OS=Linux · transports=stdio",
                "maxIterations=10 · graphKernel=false · skipMicro=false")

        when:
        def text = StartupBanner.render(data)

        then:
        StartupBanner.blockNames().every { text.contains("[" + it + "]") }
        text.contains("read_file")
        text.contains("sales")
        text.contains("主 Agent a-1")
        text.contains("session=s-1")
        text.contains("maxIterations=10")
        text.readLines().size() >= 7
    }

    def "render：空列表降级为占位，不抛错"() {
        given:
        def data = new StartupBanner.Data("ctx", null, [], null, "主 Agent a", "rt", "loop")

        when:
        def text = StartupBanner.render(data)

        then:
        noExceptionThrown()
        text.contains("[Skills]")
        text.contains("—")
    }

    def "collect：从组合根采集（无 prompts/rules 目录也不抛错）"() {
        given:
        def composed = AgentComposer.compose(
                new AgentComposer.Options("sess-banner", "test-model", false, false, ["stdio"]))

        when:
        def data = composed.banner()
        def text = StartupBanner.render(data)

        then:
        data.runtime().contains("session=sess-banner")
        data.runtime().contains("transports=stdio")
        data.loop().contains("maxIterations=")
        data.skills() != null
        text.contains("[agents]")
        text.contains("[runtime]")
        text.contains("[loop]")
    }
}
