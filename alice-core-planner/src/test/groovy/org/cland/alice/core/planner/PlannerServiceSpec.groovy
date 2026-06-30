package org.cland.alice.core.planner

import org.cland.alice.core.planner.budget.TokenBudget
import org.cland.alice.core.planner.model.ModelCapabilities
import org.cland.alice.core.planner.model.ModelSession
import org.cland.alice.core.planner.model.PlannerModelSupplier
import java.util.function.Function
import org.cland.alice.core.planner.strategy.DecisionStrategy
import org.cland.alice.core.planner.strategy.FastPathStrategy
import org.cland.alice.core.planner.strategy.SlowPathStrategy
import org.cland.alice.core.planner.strategy.StrategySelector
import org.cland.alice.core.planner.tree.ThinkingNode
import org.cland.alice.core.planner.tree.ThinkingTree
import org.cland.alice.model.Call
import org.cland.alice.model.CallStatus

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

    def "StrategySelector should route long prompt to slow path"() {
        given:
        def fastPath = Stub(DecisionStrategy)
        def slowPath = Stub(DecisionStrategy)
        slowPath.decide(_) >> Plan.builder()
                .type(Plan.Type.SLOW_PATH)
                .summary("Long")
                .addStep("FINISH", "FINISH")
                .build()
        def selector = StrategySelector.builder()
            .fastPath(fastPath)
            .slowPath(slowPath)
            .build()

        when:
        def plan = selector.select([prompt: "A" * 250])

        then:  // length > 200 → slow
        plan.type() == Plan.Type.SLOW_PATH
    }

    def "StrategySelector should route by keyword to slow path"() {
        given:
        def fastPath = Stub(DecisionStrategy) { decide(!null) >> Plan.fastPath("Fast", "FINISH", "FINISH") }
        def slowPath = Stub(DecisionStrategy) { decide(!null) >> Plan.builder().type(Plan.Type.SLOW_PATH).summary("Keyword").addStep("FINISH", "FINISH").build() }
        def selector = StrategySelector.builder()
            .fastPath(fastPath)
            .slowPath(slowPath)
            .build()

        when:
        def plan = selector.select([prompt: k])

        then:
        plan.type() == Plan.Type.SLOW_PATH

        where:
        k << ["analyze this", "compare X and Y", "evaluate options",
              "synthesize findings", "create a plan", "strategy session",
              "multi-step workflow", "complex task", "detailed report",
              "分析报告", "比较方案", "评估结果", "制定计划",
              "调整策略", "综合意见"]
    }

    def "StrategySelector should route by feedback to slow path"() {
        given:
        def fastPath = Stub(DecisionStrategy)
        def slowPath = Stub(DecisionStrategy)
        slowPath.decide(_) >> Plan.builder()
                .type(Plan.Type.SLOW_PATH)
                .summary("Feedback")
                .addStep("FINISH", "FINISH")
                .build()
        def selector = StrategySelector.builder()
            .fastPath(fastPath)
            .slowPath(slowPath)
            .build()

        when:
        def plan = selector.select([prompt: "hello", lastFeedback: "too slow"])

        then:
        plan.type() == Plan.Type.SLOW_PATH
    }

    def "StrategySelector should route by error to slow path"() {
        given:
        def fastPath = Stub(DecisionStrategy)
        def slowPath = Stub(DecisionStrategy)
        slowPath.decide(_) >> Plan.builder()
                .type(Plan.Type.SLOW_PATH)
                .summary("Error")
                .addStep("FINISH", "FINISH")
                .build()
        def selector = StrategySelector.builder()
            .fastPath(fastPath)
            .slowPath(slowPath)
            .build()

        when:
        def plan = selector.select([prompt: "hello", error: "timeout"])

        then:
        plan.type() == Plan.Type.SLOW_PATH
    }

    def "StrategySelector should accept custom complexity function"() {
        given:
        def fastPath = Stub(DecisionStrategy) { decide(_) >> Plan.fastPath("F", "FINISH", "FINISH") }
        def slowPath = Stub(DecisionStrategy) { decide(_) >> Plan.builder().type(Plan.Type.SLOW_PATH).summary("S").addStep("FINISH", "FINISH").build() }
        // 自定义函数：所有含 "custom_slow" 的走 Slow
        def selector = StrategySelector.builder()
            .fastPath(fastPath)
            .slowPath(slowPath)
            .complexityFunction({ ctx -> "custom_slow".equals(ctx.get("mode")) })
            .build()

        expect:
        selector.select([prompt: "hello", mode: "fast"]).type() == Plan.Type.FAST_PATH
        selector.select([prompt: "hello", mode: "custom_slow"]).type() == Plan.Type.SLOW_PATH
    }

    // ========================================================================
    // FastPathStrategy
    // ========================================================================

    def "FastPathStrategy should generate fast path plan"() {
        given:
        def supplier = Stub(PlannerModelSupplier) {
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
        def strategy = new FastPathStrategy(Stub(PlannerModelSupplier))

        when:
        def plan = strategy.decide([prompt: "test", result: "done"])

        then:
        plan.type() == Plan.Type.FAST_PATH
        plan.steps()[0].actionType() == "FINISH"
    }

    // ========================================================================
    // SlowPathStrategy
    // ========================================================================

    def "SlowPathStrategy should select best root child by avg_reward as next action"() {
        given:
        def tree = new ThinkingTree([prompt: "Complex multi-step analysis task"])
        def supplier = Stub(PlannerModelSupplier) {
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
        // Single next action: exactly 1 action step + FINISH = 2 steps
        plan.steps().size() == 2
        // The action step is a valid MCTS action type
        plan.steps()[0].actionType() in ["LLM_INFERENCE", "TOOL_CALL", "OBSERVE", "REVISION"]
        // Last step is FINISH
        plan.steps()[-1].actionType() == "FINISH"
        plan.metadata()["path"] == "slow"
        plan.metadata()["treeNodes"] > 0
        // Tree summary metadata present
        plan.metadata()["rootChildren"] != null
        plan.metadata()["bestAction"] != null
        plan.metadata()["bestAvgReward"] != null
    }

    def "SlowPathStrategy should output tree summary in metadata"() {
        given:
        // Tree with available tools — richer MCTS exploration
        def tree = new ThinkingTree([prompt: "Complex analysis", availableTools: ["search_web", "read_file"]])
        def supplier = Stub(PlannerModelSupplier) {
            getReasoningModel() >> ModelSession.of("gpt-4o", "test", [enable_thinking: true, reasoning_effort: "high"])
        }

        def strategy = SlowPathStrategy.builder()
            .tree(tree)
            .modelSupplier(supplier)
            .mctsIterations(8)
            .build()

        when:
        def plan = strategy.decide([prompt: "Complex analysis", availableTools: ["search_web", "read_file"]])

        then:
        plan.type() == Plan.Type.SLOW_PATH
        // Single next action step
        plan.steps().size() == 2
        plan.steps()[0].actionType() in ["LLM_INFERENCE", "TOOL_CALL", "OBSERVE", "REVISION"]
        plan.steps()[-1].actionType() == "FINISH"

        // MCTS tree summary in metadata (per output spec)
        plan.metadata()["treeNodes"] > 1
        plan.metadata()["path"] == "slow"
        plan.metadata()["mctsIterations"] == 8
        plan.metadata()["rootChildren"] != null
        int rootChildren = plan.metadata()["rootChildren"] as int
        assert rootChildren >= 1  // at least LLM_INFERENCE + OBSERVE
        plan.metadata()["bestAction"] != null
        double bestAvgReward = plan.metadata()["bestAvgReward"] as double
        assert bestAvgReward > 0
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
    // PlannerService with staticPlannerFn (SOP via Function injection)
    //
    // SopRegistry & StaticPlanner 已移至 alice-memory-vault 模块的
    // org.cland.alice.memory.sop 包。此处通过 Function 接口测试
    // PlannerService 的静态规划集成能力。
    // ========================================================================

    def "PlannerService should use injected staticPlannerFn for matching"() {
        given:
        def mockStaticPlanner = { Map<String, Object> ctx ->
            String prompt = ctx.get("prompt") as String
            if (prompt?.contains("search")) {
                return Plan.builder()
                    .type(Plan.Type.STATIC)
                    .summary("Mock static plan")
                    .metadata([sopId: "search_workflow"])
                    .addStep("TOOL_CALL", "search_web")
                    .addStep("LLM_INFERENCE", "gpt-4o")
                    .build()
            }
            return null
        } as Function<Map<String, Object>, Plan>

        def fastPath = Stub(DecisionStrategy)
        def slowPath = Stub(DecisionStrategy)
        def selector = StrategySelector.builder()
            .fastPath(fastPath)
            .slowPath(slowPath)
            .build()

        def plannerService = PlannerService.builder()
            .strategySelector(selector)
            .staticPlannerFn(mockStaticPlanner)
            .build()

        when:
        def plan = plannerService.plan([prompt: "Please search for documents"])

        then:
        plan != null
        plan.type() == Plan.Type.STATIC
        plan.steps()[0].actionType() == "TOOL_CALL"
        plan.steps()[0].target() == "search_web"
        plan.metadata()["sopId"] == "search_workflow"
    }

    def "PlannerService should fallback when staticPlannerFn returns null"() {
        given:
        def mockStaticPlanner = { null } as Function<Map<String, Object>, Plan>

        def fastPath = Stub(DecisionStrategy) {
            decide(_) >> Plan.fastPath("Fast fallback", "FINISH", "FINISH")
        }
        def slowPath = Stub(DecisionStrategy)
        def selector = StrategySelector.builder()
            .fastPath(fastPath)
            .slowPath(slowPath)
            .build()

        def plannerService = PlannerService.builder()
            .strategySelector(selector)
            .staticPlannerFn(mockStaticPlanner)
            .build()

        when:
        def plan = plannerService.plan([prompt: "no match"])

        then:
        plan != null
        plan.type() == Plan.Type.FAST_PATH
    }

    // ========================================================================
    // PlannerService Integration
    // ========================================================================

    def "PlannerService should handle simple prompt via FastPathStrategy"() {
        given:
        def supplier = Stub(PlannerModelSupplier) {
            getInstructionModel() >> ModelSession.of("gpt-4o-mini", "test")
        }
        def fastPath = new FastPathStrategy(supplier)
        def slowPath = Stub(DecisionStrategy)
        def selector = StrategySelector.builder()
            .fastPath(fastPath)
            .slowPath(slowPath)
            .build()
        def plannerService = PlannerService.builder()
            .strategySelector(selector)
            .build()

        when:
        def plan = plannerService.plan([prompt: "Hello"])

        then:
        plan != null
        plan.steps().size() >= 1
    }

    def "PlannerService should finish when result is present"() {
        given:
        def supplier = Stub(PlannerModelSupplier) {
            getInstructionModel() >> ModelSession.of("gpt-4o-mini", "test")
        }
        def fastPath = new FastPathStrategy(supplier)
        def slowPath = Stub(DecisionStrategy)
        def selector = StrategySelector.builder()
            .fastPath(fastPath)
            .slowPath(slowPath)
            .build()
        def plannerService = PlannerService.builder()
            .strategySelector(selector)
            .build()

        when:
        def plan = plannerService.plan([prompt: "test", result: "done"])

        then:
        plan.steps().size() == 1
        plan.steps()[0].actionType() == "FINISH"
    }

    // ========================================================================
    // ModelSession — alice-model bridge
    // ========================================================================

    def "ModelSession should wrap Call with correct payload"() {
        given:
        def session = ModelSession.of("gpt-4o", "Hello world", [temp: 0.7])

        expect:
        session.call() != null                              // 底层 Call 对象
        session.modelId() == "gpt-4o"
        session.prompt() == "Hello world"
        session.parameters()["temp"] == 0.7
        session.call().payload().modelId() == "gpt-4o"      // 委托到 Call.Payload
        session.call().payload().prompt() == "Hello world"
        !session.completed()                                 // 初始状态未完成
        session.error() == null
        session.response() == null                           // 无响应
    }

    def "ModelSession complete should transition to FINISHED"() {
        given:
        def session = ModelSession.of("gpt-4o", "test")

        when:
        session.complete("Final answer")

        then:
        session.completed()
        session.response() == "Final answer"
        session.call().status() == CallStatus.FINISHED
        session.call().result() != null
        session.call().result().content() == "Final answer"
    }

    def "ModelSession fail should transition to ABORTED"() {
        given:
        def session = ModelSession.of("gpt-4o", "test")

        when:
        session.fail(new RuntimeException("timeout"))

        then:
        session.completed()
        session.call().status() == CallStatus.ABORTED
    }

    def "ModelSession Builder should allow chaining"() {
        when:
        def session = ModelSession.builder()
            .modelId("gpt-4o-mini")
            .prompt("test")
            .parameters([maxTokens: 100])
            .build()

        then:
        session.modelId() == "gpt-4o-mini"
        session.prompt() == "test"
        session.parameters()["maxTokens"] == 100
    }

    // ========================================================================
    // PlannerModelSupplier — alice-model bridge
    // ========================================================================

    def "PlannerModelSupplier name() should be overridable"() {
        given:
        def supplier = Stub(PlannerModelSupplier) {
            getInstructionModel() >> ModelSession.of("gpt-4o-mini", "test")
            getReasoningModel() >> ModelSession.of("gpt-4o", "test")
            // name() 通过 Stub 设置
        }

        when:
        def name = supplier.name()

        then:
        // Spock Stub 默认方法返回默认值（字符串为 ""），name() 即使有 default impl 也会被 Stub 覆盖
        name != null
    }

    def "PlannerModelSupplier should be substitutable as ModelSupplier"() {
        given:
        // PlannerModelSupplier extends alice-model's ModelSupplier
        def supplier = Stub(PlannerModelSupplier) {
            getInstructionModel() >> ModelSession.of("gpt-4o-mini", "test")
            getReasoningModel() >> ModelSession.of("gpt-4o", "test")
        }

        expect:
        supplier instanceof org.cland.alice.model.ModelSupplier           // 编译期契约
        supplier instanceof PlannerModelSupplier
    }

    // ========================================================================
    // ModelCapabilities — alice-model bridge
    // ========================================================================

    def "ModelCapabilities should delegate to Model.Capability"() {
        expect:
        ModelCapabilities.NONE.delegate() == org.cland.alice.model.Model.Capability.NONE
        ModelCapabilities.FUNCTION_CALL.delegate() == org.cland.alice.model.Model.Capability.FUNCTION_CALL
        ModelCapabilities.STREAMING.delegate() == org.cland.alice.model.Model.Capability.STREAMING
        ModelCapabilities.VISION.delegate() == org.cland.alice.model.Model.Capability.VISION
        ModelCapabilities.ALL.delegate() == org.cland.alice.model.Model.Capability.ALL
    }

    def "ModelCapabilities supportsFunctionCall and supportsStreaming"() {
        expect:
        ModelCapabilities.FUNCTION_CALL.supportsFunctionCall()
        !ModelCapabilities.FUNCTION_CALL.supportsStreaming()
        !ModelCapabilities.STREAMING.supportsFunctionCall()
        ModelCapabilities.STREAMING.supportsStreaming()
        ModelCapabilities.ALL.supportsFunctionCall()
        ModelCapabilities.ALL.supportsStreaming()
        ModelCapabilities.VISION.supportsVision()
        !ModelCapabilities.VISION.supportsFunctionCall()
        !ModelCapabilities.NONE.supportsFunctionCall()
    }

    def "ModelCapabilities fromCapability should convert correctly"() {
        expect:
        ModelCapabilities.fromCapability(null) == ModelCapabilities.NONE
        ModelCapabilities.fromCapability(org.cland.alice.model.Model.Capability.NONE) == ModelCapabilities.NONE
        ModelCapabilities.fromCapability(org.cland.alice.model.Model.Capability.FUNCTION_CALL) == ModelCapabilities.FUNCTION_CALL
        ModelCapabilities.fromCapability(org.cland.alice.model.Model.Capability.STREAMING) == ModelCapabilities.STREAMING
        ModelCapabilities.fromCapability(org.cland.alice.model.Model.Capability.VISION) == ModelCapabilities.VISION
        ModelCapabilities.fromCapability(org.cland.alice.model.Model.Capability.ALL) == ModelCapabilities.ALL
    }
}
