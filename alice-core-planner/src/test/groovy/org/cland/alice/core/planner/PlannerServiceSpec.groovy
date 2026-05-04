package org.cland.alice.core.planner

import org.cland.alice.core.planner.budget.TokenBudget
import org.cland.alice.core.planner.model.ModelSession
import org.cland.alice.core.planner.model.ModelSupplier
import org.cland.alice.core.planner.sop.SopRegistry
import org.cland.alice.core.planner.sop.StaticPlanner
import org.cland.alice.core.planner.strategy.DecisionStrategy
import org.cland.alice.core.planner.strategy.FastPathStrategy
import org.cland.alice.core.planner.strategy.SlowPathStrategy
import org.cland.alice.core.planner.strategy.StrategySelector
import org.cland.alice.core.planner.tree.ThinkingNode
import org.cland.alice.core.planner.tree.ThinkingTree

import java.util.function.Function

import spock.lang.Specification
import spock.lang.Title

/**
 * PlannerService 核心功能规格测试。
 */
@Title("PlannerService Specification")
class PlannerServiceSpec extends Specification {

    // ========================================================================
    // Plan 值对象
    // ========================================================================

    def "Plan should be buildable with steps and type"() {
        when:
        def plan = Plan.builder()
            .type(Plan.Type.FAST_PATH)
            .summary("Test plan")
            .addStep(Plan.Step.of("LLM_INFERENCE", "gpt-4o-mini"))
            .addStep(Plan.Step.of("FINISH", "FINISH"))
            .build()

        then:
        plan.type() == Plan.Type.FAST_PATH
        plan.summary() == "Test plan"
        plan.steps().size() == 2
        plan.steps()[0].actionType() == "LLM_INFERENCE"
        plan.steps()[0].target() == "gpt-4o-mini"
        plan.steps()[1].actionType() == "FINISH"
    }

    def "Plan.fastPath should create single-step fast path plan"() {
        when:
        def plan = Plan.fastPath("Quick task", "LLM_INFERENCE", "gpt-4o-mini")

        then:
        plan.type() == Plan.Type.FAST_PATH
        plan.steps().size() == 1
        plan.steps()[0].actionType() == "LLM_INFERENCE"
    }

    def "Plan.staticPlan should create multi-step static plan"() {
        given:
        def steps = [
            Plan.Step.of("TOOL_CALL", "search_web"),
            Plan.Step.of("LLM_INFERENCE", "gpt-4o"),
            Plan.Step.of("FINISH", "FINISH")
        ]

        when:
        def plan = Plan.staticPlan("Search workflow", steps)

        then:
        plan.type() == Plan.Type.STATIC
        plan.steps().size() == 3
    }

    def "Plan.Step should convert to action map"() {
        when:
        def step = Plan.Step.of("TOOL_CALL", "search_api", [query: "test"], "Search for results")
        def actionMap = step.toActionMap()

        then:
        actionMap["type"] == "TOOL_CALL"
        actionMap["target"] == "search_api"
        actionMap["parameters"]["query"] == "test"
        actionMap["thought"] == "Search for results"
    }

    // ========================================================================
    // ThinkingNode
    // ========================================================================

    def "ThinkingNode should be buildable and compute UCT"() {
        given:
        def parent = ThinkingNode.builder()
            .state([:])
            .actionType("ROOT")
            .actionTarget("ROOT")
            .build()

        def child = ThinkingNode.builder()
            .state([prompt: "test"])
            .actionType("LLM_INFERENCE")
            .actionTarget("gpt-4o")
            .thought("Reasoning step")
            .reward(2.0)
            .visits(3)
            .parent(parent)
            .build()

        expect:
        child.nodeId() > 0
        child.actionType() == "LLM_INFERENCE"
        child.actionTarget() == "gpt-4o"
        child.thought() == "Reasoning step"
        child.reward() == 2.0
        child.visits() == 3
        child.parent() == parent
        child.isLeaf()
        !child.isRoot()

        // Double.MAX_VALUE for unvisited nodes
        ThinkingNode.builder().build().uct(10, Math.sqrt(2)) == Double.MAX_VALUE
    }

    def "ThinkingNode should support MCTS operations"() {
        given:
        def node = ThinkingNode.builder()
            .reward(0.0)
            .visits(0)
            .build()

        when:
        node.addReward(1.5)
        node.incrementVisits()
        node.incrementVisits()
        node.setObservation("Success")
        node.markExpanded()

        then:
        node.reward() == 1.5
        node.visits() == 2
        node.observation() == "Success"
        node.expanded()
    }

    // ========================================================================
    // ThinkingTree
    // ========================================================================

    def "ThinkingTree should be constructable with root state"() {
        given:
        def state = [prompt: "Hello", model: "gpt-4o"]

        when:
        def tree = new ThinkingTree(state)

        then:
        tree.root() != null
        tree.root().actionType() == "ROOT"
        tree.nodeCount() == 1
        tree.depth() == 0
        tree.allNodes().size() == 1
    }

    def "ThinkingTree expand should add child nodes"() {
        given:
        def tree = new ThinkingTree([:])

        def generators = [
            { ThinkingNode p -> ThinkingNode.builder().actionType("LLM_INFERENCE").actionTarget("gpt-4o").build() } as Function<ThinkingNode, ThinkingNode>,
            { ThinkingNode p -> ThinkingNode.builder().actionType("TOOL_CALL").actionTarget("search").build() } as Function<ThinkingNode, ThinkingNode>
        ]

        when:
        tree.expand(tree.root(), generators)

        then:
        tree.nodeCount() == 3
        tree.root().expanded()
        tree.getChildren(tree.root()).size() == 2
        tree.depth() == 2
    }

    def "ThinkingTree backpropagate should update ancestors"() {
        given:
        def tree = new ThinkingTree([:])
        tree.expand(tree.root(), [
            { ThinkingNode p -> ThinkingNode.builder().actionType("LLM_INFERENCE").actionTarget("test").build() } as Function<ThinkingNode, ThinkingNode>
        ])
        def child = tree.getChildren(tree.root())[0]

        when:
        tree.backpropagate(child, 2.0)

        then:
        child.reward() == 2.0
        child.visits() == 1
        tree.root().reward() == 2.0
        tree.root().visits() == 1
    }

    def "ThinkingTree bestPath should return path from root to leaf"() {
        given:
        def tree = new ThinkingTree([:])
        tree.expand(tree.root(), [
            { ThinkingNode p -> ThinkingNode.builder().actionType("LLM_INFERENCE").actionTarget("gpt-4o").reward(1.0).visits(5).build() } as Function<ThinkingNode, ThinkingNode>,
            { ThinkingNode p -> ThinkingNode.builder().actionType("TOOL_CALL").actionTarget("search").reward(0.5).visits(3).build() } as Function<ThinkingNode, ThinkingNode>
        ])

        when:
        def path = tree.bestPath()

        then:
        path.size() >= 1
        path[0].isRoot()
    }

    // ========================================================================
    // StrategySelector
    // ========================================================================

    def "StrategySelector should route simple tasks to fast path"() {
        given:
        def fastPath = Stub(DecisionStrategy)
        fastPath.decide(_) >> Plan.fastPath("Fast", "FINISH", "FINISH")

        def slowPath = Stub(DecisionStrategy)
        def selector = StrategySelector.builder()
            .fastPath(fastPath)
            .slowPath(slowPath)
            .build()

        when:
        def plan = selector.select([prompt: "hello"])

        then:
        plan.type() == Plan.Type.FAST_PATH
    }

    def "StrategySelector should route complex tasks to slow path"() {
        given:
        def fastPath = Stub(DecisionStrategy)
        def slowPath = Stub(DecisionStrategy)
        slowPath.decide(_) >> Plan.builder()
                .type(Plan.Type.SLOW_PATH)
                .summary("Complex")
                .addStep("FINISH", "FINISH")
                .build()

        def selector = StrategySelector.builder()
            .fastPath(fastPath)
            .slowPath(slowPath)
            .build()

        when:
        def plan = selector.select([prompt: "Analyze the complex multi-step reasoning task"])

        then:
        plan.type() == Plan.Type.SLOW_PATH
    }

    // ========================================================================
    // FastPathStrategy
    // ========================================================================

    def "FastPathStrategy should generate fast path plan"() {
        given:
        def supplier = Stub(ModelSupplier) {
            getInstructionModel() >> ModelSession.of("gpt-4o-mini", "test")
        }
        def strategy = new FastPathStrategy(supplier)

        when:
        def plan = strategy.decide([prompt: "What is Java?"])

        then:
        plan.type() == Plan.Type.FAST_PATH
        plan.steps().size() == 2
        plan.steps()[0].actionType() == "LLM_INFERENCE"
        plan.steps()[1].actionType() == "FINISH"
    }

    def "FastPathStrategy should finish if result present"() {
        given:
        def strategy = new FastPathStrategy(Stub(ModelSupplier))

        when:
        def plan = strategy.decide([prompt: "test", result: "done"])

        then:
        plan.type() == Plan.Type.FAST_PATH
        plan.steps()[0].actionType() == "FINISH"
    }

    // ========================================================================
    // SlowPathStrategy
    // ========================================================================

    def "SlowPathStrategy should generate MCTS plan"() {
        given:
        def tree = new ThinkingTree([prompt: "Complex multi-step analysis task"])
        def supplier = Stub(ModelSupplier) {
            getReasoningModel() >> ModelSession.of("gpt-4o", "test")
        }

        def strategy = SlowPathStrategy.builder()
            .tree(tree)
            .modelSupplier(supplier)
            .mctsIterations(5)
            .build()

        when:
        def plan = strategy.decide([prompt: "Complex multi-step analysis task"])

        then:
        plan.type() == Plan.Type.SLOW_PATH
        plan.steps().size() >= 1
        plan.metadata()["path"] == "slow"
        plan.metadata()["treeNodes"] > 0
    }

    // ========================================================================
    // TokenBudget
    // ========================================================================

    def "TokenBudget should enforce limits"() {
        given:
        def budget = TokenBudget.of(5, 10)

        expect:
        !budget.isExhausted()
        budget.maxTokens() == 5
        budget.maxDepth() == 10
        budget.consumedTokens() == 0
    }

    def "TokenBudget should track consumption"() {
        given:
        def budget = TokenBudget.of(3, 10)
        def node = ThinkingNode.builder().build()

        when:
        budget.consume(node)
        budget.consume(node)

        then:
        budget.consumedTokens() == 2
        !budget.isExhausted()

        when:
        budget.consume(node)

        then:
        budget.consumedTokens() == 3
        budget.isExhausted()
    }

    def "TokenBudget.unlimited should never exhaust"() {
        given:
        def budget = TokenBudget.unlimited()
        def node = ThinkingNode.builder().build()

        expect:
        budget.isUnlimited()
        !budget.isExhausted()

        when:
        100.times { budget.consume(node) }

        then:
        !budget.isExhausted()
    }

    // ========================================================================
    // SopRegistry & StaticPlanner
    // ========================================================================

    def "SopRegistry should register and match templates"() {
        given:
        def registry = new SopRegistry()
        def template = SopRegistry.SopTemplate.builder()
            .id("weather_query")
            .description("Get weather information")
            .keywords(["weather", "temperature", "forecast"])
            .addStep("TOOL_CALL", "get_weather")
            .addStep("LLM_INFERENCE", "gpt-4o-mini")
            .build()

        when:
        registry.register(template)

        then:
        registry.get("weather_query") == template
        registry.ids().contains("weather_query")

        when:
        def matched = registry.match("What is the weather today?")

        then:
        matched != null
        matched.id() == "weather_query"
    }

    def "StaticPlanner should generate plan from SOP"() {
        given:
        def registry = new SopRegistry()
        registry.register(SopRegistry.SopTemplate.builder()
            .id("search_workflow")
            .keywords(["search", "find", "lookup"])
            .addStep("TOOL_CALL", "search_web")
            .addStep("LLM_INFERENCE", "gpt-4o")
            .build())

        def staticPlanner = new StaticPlanner(registry)

        when:
        def plan = staticPlanner.plan([prompt: "Please search for documents"])

        then:
        plan != null
        plan.type() == Plan.Type.STATIC
        plan.steps().size() == 3 // 2 steps + auto FINISH
        plan.steps()[0].actionType() == "TOOL_CALL"
        plan.steps()[0].target() == "search_web"
        plan.metadata()["sopId"] == "search_workflow"
    }

    // ========================================================================
    // ReAct (Backward Compatibility)
    // ========================================================================

    def "ReAct should maintain backward compatible proposeNext API"() {
        given:
        def react = new ReAct()

        when:
        def result = react.proposeNext([prompt: "Hello"])

        then:
        result.containsKey("type")
        result.containsKey("target")
        result["type"] == "LLM_INFERENCE" || result["type"] == "FINISH"
    }

    def "ReAct should return FINISH when result exists"() {
        given:
        def react = new ReAct()

        when:
        def result = react.proposeNext([prompt: "Hello", result: "Done"])

        then:
        result["type"] == "FINISH"
    }

    // ========================================================================
    // PlannerService Integration
    // ========================================================================

    def "PlannerService should handle simple prompt"() {
        given:
        def react = new ReAct()

        when:
        def plan = react.plannerService().plan("Hello")

        then:
        plan != null
        plan.steps().size() >= 1
    }

    def "PlannerService should finish when result is present"() {
        given:
        def react = new ReAct()

        when:
        def plan = react.plannerService().plan([prompt: "test", result: "done"])

        then:
        plan.steps().size() == 1
        plan.steps()[0].actionType() == "FINISH"
    }
}
