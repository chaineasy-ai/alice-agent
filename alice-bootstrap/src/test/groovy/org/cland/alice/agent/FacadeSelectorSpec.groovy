/*
 * FacadeSelectorSpec — 覆盖 FacadeSelector 中未测试的分支
 *
 * 测试目标：extractFacadeArg, filterBootstrapArgs, findFacade 无匹配, launch --facade
 */
package org.cland.alice.agent

import spock.lang.Specification
import spock.lang.Title
import org.cland.alice.model.Call
import org.cland.alice.model.ModelProvider

@Title("FacadeSelector coverage supplement")
class FacadeSelectorSpec extends Specification {

    def setupSpec() {
        // TUI-launching 测试使用 headless 模式：装配验证后即返回，不进入交互输入循环
        System.setProperty("alice.tui.headless", "true")
    }

    def cleanupSpec() {
        System.clearProperty("alice.tui.headless")
    }


    // ================================================================
    // FacadeSelector.launch() — SPI 路由补充
    // ================================================================

    def "launch with --facade cli explicit"() {
        expect:
        FacadeSelector.launch(["--facade", "cli"] as String[]) == AliceApp.EXIT_PARAM_ERROR
    }

    def "launch with --facade tui explicit"() {
        expect:
        FacadeSelector.launch(["--facade", "tui"] as String[]) == AliceApp.EXIT_SUCCESS
    }

    def "launch with --facade unknown name returns param error"() {
        expect:
        FacadeSelector.launch(["--facade", "nonexistent"] as String[]) == AliceApp.EXIT_PARAM_ERROR
    }

    def "launch with -t short flag delegates to TUI"() {
        expect:
        FacadeSelector.launch(["-t"] as String[]) == AliceApp.EXIT_SUCCESS
    }

    def "launch with --cli short flag delegates to CLI"() {
        expect:
        FacadeSelector.launch(["-c"] as String[]) == AliceApp.EXIT_PARAM_ERROR
    }

    def "launch with run subcommand after --facade"() {
        given: "离线 hermetic 模型 stub（阻断真实 LLM 网络调用）"
        def provider = ModelProvider.getInstance()
        def stub = {
            Call call ->
                Call.Response.textOnly("[FINISH]", new Call.TokenUsage(0, 0, 0), [:]) as Call.Response
        } as org.cland.alice.model.ModelSupplier
        provider.registerSupplier(stub)
        provider.setRouter({ String modelId -> stub.name() })

        expect:
        FacadeSelector.launch(["--facade", "cli", "run", "test"] as String[]) == AliceApp.EXIT_SUCCESS

        cleanup:
        ModelProvider.reset()
    }

    def "launch filters --facade from facade args"() {
        expect:
        FacadeSelector.launch(["--facade", "cli", "--cli"] as String[]) == AliceApp.EXIT_PARAM_ERROR
    }

    // ================================================================
    // 间接测试 filterBootstrapArgs / extractFacadeArg 的行为
    // ================================================================

    def "launch strips all bootstrap-only args before passing to facade"() {
        expect:
        // --tui should be stripped; "run" remains and gets dispatched
        FacadeSelector.launch(["--tui", "run", "hello"] as String[]) == AliceApp.EXIT_SUCCESS
    }

    def "launch strips -t and passes remaining args"() {
        expect:
        FacadeSelector.launch(["-t", "run", "world"] as String[]) == AliceApp.EXIT_SUCCESS
    }
}
