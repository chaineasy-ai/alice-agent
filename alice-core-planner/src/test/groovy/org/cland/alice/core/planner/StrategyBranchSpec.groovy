package org.cland.alice.core.planner

import org.cland.alice.core.planner.model.ModelCapabilities
import org.cland.alice.core.planner.model.ModelSession
import org.cland.alice.core.planner.model.PlannerModelSupplier
import org.cland.alice.core.planner.strategy.DecisionStrategy
import org.cland.alice.core.planner.strategy.FastPathStrategy
import org.cland.alice.core.planner.strategy.SlowPathStrategy
import org.cland.alice.core.planner.strategy.StrategySelector
import org.cland.alice.core.planner.tree.MctsEngine
import org.cland.alice.core.planner.tree.ThinkingNode
import org.cland.alice.core.planner.tree.ThinkingTree
import org.cland.alice.model.Call
import org.cland.alice.model.Call.Response
import org.cland.alice.model.Call.TokenUsage
import org.cland.alice.model.Model.Capability
import spock.lang.Specification
import spock.lang.Title

/**
 * 分支覆盖补充测试 — 针对 FastPathStrategy 的 LLM 意图分类路径、
 * SlowPathStrategy 的展开器/回退路径、PlannerService 便捷入口等未覆盖分支。
 */
@Title("Planner Branch Supplement")
class StrategyBranchSpec extends Specification {

    private static final TokenUsage TU = new TokenUsage(10, 5, 15)

    private Response text(String content) {
        Response.textOnly(content, TU, [:])
    }

    // ========================================================================
    // ModelCapabilities — fromCapability 值表全量匹配
    // ========================================================================

    def "ModelCapabilities fromCapability matches every constant"() {
        expect:
        ModelCapabilities.fromCapability(Capability.NONE) == ModelCapabilities.NONE
        ModelCapabilities.fromCapability(Capability.FUNCTION_CALL) == ModelCapabilities.FUNCTION_CALL
        ModelCapabilities.fromCapability(Capability.VISION) == ModelCapabilities.VISION
        ModelCapabilities.fromCapability(Capability.STREAMING) == ModelCapabilities.STREAMING
        ModelCapabilities.fromCapability(Capability.ALL) == ModelCapabilities.ALL
        ModelCapabilities.fromCapability(null) == ModelCapabilities.NONE
    }

    // ========================================================================
    // FastPathStrategy — LLM 意图分类路径
    // ========================================================================

    def "FastPath classify should parse multi-word intent chain and forward context"() {
        given:
        def supplier = Stub(PlannerModelSupplier) {
            getInstructionModel() >> ModelSession.of("gpt-4o-mini", "x",
                    [enable_thinking: true, reasoning_effort: "high"])
            request(_ as Call) >> { Call c -> text("SEARCH CODE") }
        }
        def strategy = new FastPathStrategy(supplier)

        when:
        def plan = strategy.decide([prompt: "P", plannerPrompt: "PP", availableTools: ["web"],
                                    lastObservation: "obs", lastActionResult: "res", error: ""])

        then:
        plan.type() == Plan.Type.FAST_PATH
        plan.steps().size() == 2
        plan.steps()[0].actionType() == "TOOL_CALL"
        plan.steps()[0].target() == "gpt-4o-mini"
        plan.steps()[0].parameters()["prompt"] == "P"
        plan.steps()[0].parameters()["lastObservation"] == "obs"
        plan.steps()[0].parameters()["availableTools"] == ["web"]
        plan.steps()[0].parameters()["enable_thinking"] == true
        plan.steps()[0].parameters()["reasoning_effort"] == "high"
        plan.metadata()["intent"] == "SEARCH"
        plan.metadata()["intentChain"] == ["SEARCH", "CODE"]
        plan.metadata()["plannerRawResponse"] == "SEARCH CODE"
        plan.metadata()["path"] == "fast"
    }

    def "FastPath classify with unrecognized words should fall back to ANALYZE"() {
        given:
        def supplier = Stub(PlannerModelSupplier) {
            getInstructionModel() >> ModelSession.of("gpt-4o-mini", "x")
            request(_ as Call) >> { Call c -> text("BANANA XY") }
        }
        def strategy = new FastPathStrategy(supplier)

        when:
        def plan = strategy.decide([prompt: "P"])

        then:
        plan.steps()[0].actionType() == "LLM_INFERENCE"
        plan.metadata()["intent"] == "ANALYZE"
    }

    def "FastPath classify with model exception should fall back to ANALYZE"() {
        given:
        def supplier = Stub(PlannerModelSupplier) {
            getInstructionModel() >> ModelSession.of("gpt-4o-mini", "x")
            request(_ as Call) >> { throw new RuntimeException("model boom") }
        }
        def strategy = new FastPathStrategy(supplier)

        when:
        def plan = strategy.decide([prompt: "P"])

        then:
        plan.steps()[0].actionType() == "LLM_INFERENCE"
        plan.steps()[0].target() == "gpt-4o-mini"
    }

    def "FastPath classify with blank content should fall back to ANALYZE"() {
        given:
        def strategy = new FastPathStrategy(Stub(PlannerModelSupplier) {
            getInstructionModel() >> ModelSession.of("gpt-4o-mini", "x")
            request(_ as Call) >> { Call c -> text("   ") }
        })

        when:
        def plan = strategy.decide([prompt: "P"])

        then:
        plan.steps()[0].actionType() == "LLM_INFERENCE"
    }

    def "FastPath classify with null content should fall back to ANALYZE"() {
        given:
        def strategy = new FastPathStrategy(Stub(PlannerModelSupplier) {
            getInstructionModel() >> ModelSession.of("gpt-4o-mini", "x")
            request(_ as Call) >> { Call c -> text(null) }
        })

        when:
        def plan = strategy.decide([prompt: "P"])

        then:
        plan.steps()[0].actionType() == "LLM_INFERENCE"
    }

    def "FastPath decide with null instruction session should use default model id"() {
        given:
        def supplier = Stub(PlannerModelSupplier) {
            getInstructionModel() >> null
            request(_ as Call) >> { Call c -> text("ANALYZE") }
        }
        def strategy = new FastPathStrategy(supplier)

        when:
        def plan = strategy.decide([prompt: "P"])

        then:
        plan.steps()[0].actionType() == "LLM_INFERENCE"
        plan.steps()[0].target() == "gpt-4o-mini"
    }

    def "FastPath decide with empty result string should continue classifying"() {
        given:
        def supplier = Stub(PlannerModelSupplier) {
            getInstructionModel() >> ModelSession.of("gpt-4o-mini", "x")
            request(_ as Call) >> { Call c -> text("SEARCH") }
        }
        def strategy = new FastPathStrategy(supplier)

        when:
        def plan = strategy.decide([prompt: "P", result: ""])

        then:
        plan.steps().size() == 2
        plan.steps()[0].actionType() == "TOOL_CALL"
    }

    // ========================================================================
    // SlowPathStrategy — 自定义展开器/模拟器驱动的分支
    // ========================================================================

    private Plan decideWithCandidates(List<String> actionTypes, double score) {
        def tree = new ThinkingTree([prompt: "complex task"])
        def supplier = Stub(PlannerModelSupplier) {
            getReasoningModel() >> ModelSession.of("gpt-4o", "x")
        }
        def strategy = SlowPathStrategy.builder()
            .tree(tree)
            .modelSupplier(supplier)
            .mctsIterations(1)
            .expander({ ThinkingNode leaf ->
                actionTypes.collect { String t ->
                    ThinkingNode.builder().actionType(t).actionTarget(t).build()
                }
            } as MctsEngine.Expander)
            .simulator({ ThinkingNode n -> score } as MctsEngine.Simulator)
            .build()
        return strategy.decide([prompt: "complex task"])
    }

    def "SlowPath best child with REVISION action should map to REVISION intent"() {
        when:
        def plan = decideWithCandidates(["REVISION"], 80.0d)

        then:
        plan.type() == Plan.Type.SLOW_PATH
        plan.steps()[0].actionType() == "REVISION"
        plan.metadata()["bestAction"] == "REVISION->REVISION"
        plan.metadata()["bestAvgReward"] == 80.0d
    }

    def "SlowPath best child with OBSERVE action should map to ANALYZE intent"() {
        when:
        def plan = decideWithCandidates(["OBSERVE"], 80.0d)

        then:
        plan.steps()[0].actionType() == "LLM_INFERENCE"
        plan.metadata()["bestAction"] == "OBSERVE->OBSERVE"
    }

    def "SlowPath best child with unknown action type should map to ANALYZE via default"() {
        when:
        def plan = decideWithCandidates(["MYSTERY"], 80.0d)

        then:
        plan.steps()[0].actionType() == "LLM_INFERENCE"
        plan.metadata()["bestAction"] == "MYSTERY->MYSTERY"
    }

    def "SlowPath fallback without reasoning session should use default model"() {
        given:
        def tree = new ThinkingTree([prompt: "complex task"])
        def strategy = SlowPathStrategy.builder()
            .tree(tree)
            .modelSupplier(Stub(PlannerModelSupplier) { getReasoningModel() >> null })
            .mctsIterations(0)
            .build()

        when:
        def plan = strategy.decide([prompt: "complex task"])

        then:
        plan.steps().size() == 2
        plan.steps()[0].actionType() == "LLM_INFERENCE"
        plan.steps()[0].target() == "gpt-4o-mini"
        plan.steps()[0].parameters()["prompt"] == "complex task"
        plan.metadata()["treeNodes"] == 1
    }

    def "SlowPath fallback should forward thinking params from reasoning session"() {
        given:
        def tree = new ThinkingTree([prompt: "complex task"])
        def strategy = SlowPathStrategy.builder()
            .tree(tree)
            .modelSupplier(Stub(PlannerModelSupplier) {
                getReasoningModel() >> ModelSession.of("deep-reasoner", "x",
                        [enable_thinking: true, reasoning_effort: "high"])
            })
            .mctsIterations(0)
            .build()

        when:
        def plan = strategy.decide([prompt: "complex task"])

        then:
        plan.steps()[0].target() == "deep-reasoner"
        plan.steps()[0].parameters()["enable_thinking"] == true
        plan.steps()[0].parameters()["reasoning_effort"] == "high"
    }

    def "SlowPath default macro expander should emit tool candidates when tools available"() {
        given:
        def tree = new ThinkingTree([prompt: "analyze", availableTools: ["search_web", "read_file"]])
        def supplier = Stub(PlannerModelSupplier) {
            getReasoningModel() >> ModelSession.of("gpt-4o", "x")
        }
        def strategy = SlowPathStrategy.builder()
            .tree(tree)
            .modelSupplier(supplier)
            .mctsIterations(3)
            .build()

        when:
        def plan = strategy.decide([prompt: "analyze", availableTools: ["search_web", "read_file"]])

        then:
        plan.metadata()["rootChildren"] >= 3   // LLM_INFERENCE + 2 tools + OBSERVE
        plan.metadata()["mctsIterations"] == 3
    }

    // ========================================================================
    // PlannerService — 便捷入口与边界
    // ========================================================================

    private PlannerService makePlanner() {
        def fastPath = Stub(DecisionStrategy) {
            decide(_) >> Plan.fastPath("F", Plan.Intent.FINISH, "FINISH")
        }
        def selector = StrategySelector.builder()
            .fastPath(fastPath)
            .slowPath(Stub(DecisionStrategy))
            .build()
        PlannerService.builder().strategySelector(selector).build()
    }

    def "PlannerService plan(String) convenience entry should delegate"() {
        given:
        def planner = makePlanner()

        when:
        def plan = planner.plan("hello")

        then:
        plan != null
        plan.type() == Plan.Type.FAST_PATH
    }

    def "PlannerService plan(String, model) convenience entry should delegate"() {
        given:
        def planner = makePlanner()

        when:
        def plan = planner.plan("hello", "gpt-4o-mini")

        then:
        plan != null
    }

    def "PlannerService plan with null-valued result key should not finish early"() {
        given:
        def planner = makePlanner()

        when:
        def plan = planner.plan([prompt: "x", result: null])

        then:
        plan != null
    }

    // ========================================================================
    // Plan.Step — 无参/无 thought 的 toActionMap
    // ========================================================================

    def "Plan.Step toActionMap without parameters or thought omits those keys"() {
        when:
        def actionMap = Plan.Step.of(Plan.Intent.SEARCH, "search_web").toActionMap()

        then:
        actionMap["type"] == "TOOL_CALL"
        actionMap["target"] == "search_web"
        !actionMap.containsKey("parameters")
        !actionMap.containsKey("thought")
    }

    def "Plan.Step of with null parameters should normalize to empty map"() {
        when:
        def step = Plan.Step.of(Plan.Intent.FINISH, "FINISH", null)

        then:
        step.parameters().isEmpty()
    }

    // ========================================================================
    // MctsEngine.Builder — 必需组件校验
    // ========================================================================

    def "MctsEngine builder without expander should throw"() {
        when:
        MctsEngine.builder(new ThinkingTree([:]))
            .simulator({ ThinkingNode n -> 1.0d } as MctsEngine.Simulator)
            .build()

        then:
        thrown(IllegalStateException)
    }

    def "MctsEngine builder without simulator should throw"() {
        when:
        MctsEngine.builder(new ThinkingTree([:]))
            .expander({ ThinkingNode leaf -> [] } as MctsEngine.Expander)
            .build()

        then:
        thrown(IllegalStateException)
    }
}
