package org.cland.alice.core.agent.tool

import com.fasterxml.jackson.databind.ObjectMapper
import org.cland.alice.core.planner.Plan
import org.cland.alice.core.planner.PlannerService
import org.cland.alice.core.planner.strategy.DecisionStrategy
import org.cland.alice.core.planner.strategy.StrategySelector
import org.cland.alice.tool.gateway.ToolRegistry
import org.cland.alice.tool.gateway.engine.ToolDiscovery
import spock.lang.Specification
import spock.lang.Title

/**
 * 规划工具（D10）测试 — 注册形态、输出 JSON 结构、坏 context 容错。
 */
@Title("PlanTool — tool 层 plan 工具")
class PlanToolSpec extends Specification {

    private static final ObjectMapper MAPPER = new ObjectMapper()

    private PlannerService finishPlanner() {
        def respond = { Plan.builder().type(Plan.Type.FAST_PATH).summary("Immediate finish")
                .addStep(Plan.Step.of(Plan.Intent.FINISH, "FINISH")).build() }
        def fastPath = Stub(DecisionStrategy) { decide(_) >> respond() }
        def selector = StrategySelector.builder()
                .fastPath(fastPath)
                .slowPath(Stub(DecisionStrategy) { decide(_) >> respond() })
                .build()
        PlannerService.builder().strategySelector(selector).build()
    }

    def "PlanTool registers under name plan via ToolDiscovery"() {
        given:
        def registry = new ToolRegistry()
        def tool = new PlanTool(finishPlanner())

        when:
        new ToolDiscovery(registry).scanAndRegister([tool] as List)

        then:
        registry.hasTool("plan")
        registry.allTools().size() == 1
    }

    def "PlanTool returns JSON with type and ordered steps"() {
        given:
        def planner = finishPlanner()
        def tool = new PlanTool(planner)

        when:
        def out = tool.plan("Say hello", null)
        def json = MAPPER.readValue(out, Map)

        then:
        json["type"] == "FAST_PATH"
        (json["steps"] as List).size() == 1
        (json["steps"] as List)[0]["intent"] == "FINISH"
        json.containsKey("metadata")
    }

    def "PlanTool merges JSON context into planning context"() {
        given:
        def tool = new PlanTool(finishPlanner())

        when:
        def out = tool.plan("Search task", '{"availableTools": ["search_web"], "error": "x"}')

        then:
        MAPPER.readValue(out, Map)["type"] == "FAST_PATH"
    }

    def "PlanTool tolerates malformed context JSON"() {
        given:
        def tool = new PlanTool(finishPlanner())

        when:
        def out = tool.plan("Task", "not-json{")

        then: "falls back to prompt-only planning"
        MAPPER.readValue(out, Map)["type"] == "FAST_PATH"
    }
}
