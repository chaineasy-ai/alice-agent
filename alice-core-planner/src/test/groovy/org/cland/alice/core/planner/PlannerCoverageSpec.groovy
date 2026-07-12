package org.cland.alice.core.planner

import org.cland.alice.core.planner.budget.TokenBudget
import org.cland.alice.core.planner.model.ModelCapabilities
import org.cland.alice.core.planner.model.ModelSession
import org.cland.alice.core.planner.model.PlannerModelSupplier
import org.cland.alice.core.planner.strategy.DecisionStrategy
import org.cland.alice.core.planner.strategy.FastPathStrategy
import org.cland.alice.core.planner.strategy.SlowPathStrategy
import org.cland.alice.core.planner.strategy.StrategySelector
import org.cland.alice.core.planner.tree.ThinkingNode
import org.cland.alice.core.planner.tree.ThinkingTree
import org.cland.alice.model.CallStatus
import org.cland.alice.model.Call

import java.util.function.Function

import spock.lang.Specification
import spock.lang.Title

/**
 * 覆盖率补充测试 — 针对 PlannerService, ThinkingTree, TokenBudget, ThinkingNode,
 * FastPathStrategy, SlowPathStrategy 中尚未覆盖的分支/路径。
 */
@Title("Planner Coverage Supplement")
class PlannerCoverageSpec extends Specification {

    // ========================================================================
    // TokenBudget — 补充 Edge Cases
    // ========================================================================

    def "TokenBudget constructor should reject non-positive values"() {
        when: TokenBudget.of(0, 10)
        then: thrown(IllegalArgumentException)

        when: TokenBudget.of(10, 0)
        then: thrown(IllegalArgumentException)

        when: TokenBudget.of(-1, 10)
        then: thrown(IllegalArgumentException)
    }

    def "TokenBudget remainingTokens should compute correctly"() {
        given:
        def budget = TokenBudget.of(10, 100)
        def node = ThinkingNode.builder().build()

        expect:
        budget.remainingTokens() == 10

        when:
        budget.consume(node)

        then:
        budget.remainingTokens() == 9
    }

    def "TokenBudget isExhausted by depth"() {
        given:
        // depth 1 即耗尽
        def budget = TokenBudget.of(100, 1)
        def node = ThinkingNode.builder().build()

        expect:
        !budget.isExhausted()

        when: budget.consume(node)
        then: budget.isExhausted()  // depth after consume >= 1
    }

    def "TokenBudget isExhausted returns false before consume"() {
        given:
        def budget = TokenBudget.of(10, 100)

        expect:
        !budget.isExhausted()
    }

    def "TokenBudget consume with unlimited does nothing"() {
        given:
        def budget = TokenBudget.unlimited()
        def node = ThinkingNode.builder().build()

        when:
        budget.consume(node)

        then:
        budget.consumedTokens() == 0
        budget.currentDepth() == 0
        !budget.isExhausted()
    }

    def "TokenBudget consume with null node should throw"() {
        given:
        def budget = TokenBudget.of(10, 100)

        when: budget.consume(null)
        then: thrown(NullPointerException)
    }

    def "TokenBudget reset should clear counters"() {
        given:
        def budget = TokenBudget.of(10, 100)
        def node = ThinkingNode.builder().build()

        when:
        budget.consume(node)
        budget.consume(node)
        budget.reset()

        then:
        budget.consumedTokens() == 0
        budget.currentDepth() == 0
        !budget.isExhausted()
    }

    def "TokenBudget toString should include state"() {
        given:
        def budget = TokenBudget.of(10, 100)

        expect:
        budget.toString().contains("10")
        budget.toString().contains("100")
    }

    def "TokenBudget unlimited isUnlimited"() {
        expect:
        TokenBudget.unlimited().isUnlimited()
        !TokenBudget.of(10, 100).isUnlimited()
    }

    // ========================================================================
    // ThinkingTree — 补充 Edge Cases
    // ========================================================================

    def "ThinkingTree evaluate should set reward on node"() {
        given:
        def tree = new ThinkingTree([prompt: "test"])
        def node = ThinkingNode.builder()
            .actionType("LLM_INFERENCE")
            .actionTarget("gpt-4o")
            .build()

        when:
        tree.evaluate(node, new Function<Map<String, Object>, Double>() {
            @Override
            Double apply(Map<String, Object> state) { return 3.14d }
        })

        then:
        node.reward() == 3.14d
    }

    def "ThinkingTree expand already expanded node should warn and skip"() {
        given:
        def tree = new ThinkingTree([:])
        // First expand
        tree.expand(tree.root(), [
            { ThinkingNode p -> ThinkingNode.builder().actionType("A1").actionTarget("T1").build() } as Function
        ])

        when: // Second expand on same node should skip
        tree.expand(tree.root(), [
            { ThinkingNode p -> ThinkingNode.builder().actionType("A2").actionTarget("T2").build() } as Function
        ])

        then: // No new children added
        tree.getChildren(tree.root()).size() == 1
        tree.nodeCount() == 2
    }

    def "ThinkingTree bestChildByAvgReward with no children returns null"() {
        given:
        def tree = new ThinkingTree([:])

        expect:
        tree.bestChildByAvgReward() == null
    }

    def "ThinkingTree bestChildByAvgReward with all zero-visit children returns null"() {
        given:
        def tree = new ThinkingTree([:])
        tree.expand(tree.root(), [
            { ThinkingNode p -> ThinkingNode.builder().actionType("A").actionTarget("T").visits(0).reward(0.0).build() } as Function
        ])

        expect:
        tree.bestChildByAvgReward() == null
    }

    def "ThinkingTree selectBestChild with no children returns null"() {
        given:
        def tree = new ThinkingTree([:])

        expect:
        tree.selectBestChild(tree.root()) == null
    }

    def "ThinkingTree pathFromRoot for root node"() {
        given:
        def tree = new ThinkingTree([:])

        when:
        def path = tree.pathFromRoot(tree.root())

        then:
        path.size() == 1
        path[0].isRoot()
    }

    def "ThinkingTree bestLeaf returns root for empty tree"() {
        given:
        def tree = new ThinkingTree([:])

        expect:
        tree.bestLeaf().isRoot()
    }

    def "ThinkingTree forEach visits all nodes"() {
        given:
        def tree = new ThinkingTree([:])
        tree.expand(tree.root(), [
            { ThinkingNode p -> ThinkingNode.builder().actionType("LLM").actionTarget("test").build() } as Function
        ])

        def visited = []

        when:
        tree.forEach { node -> visited.add(node.actionType()) }

        then:
        visited.containsAll(["ROOT", "LLM"])
        visited.size() == 2
    }

    def "ThinkingTree serialize returns flat records"() {
        given:
        def tree = new ThinkingTree([prompt: "serialize_test"])
        tree.expand(tree.root(), [
            { ThinkingNode p -> ThinkingNode.builder().actionType("TOOL").actionTarget("search").build() } as Function
        ])

        when:
        def records = tree.serialize()

        then:
        records.size() == 2
        records[0]["actionType"] == "ROOT"
        records[0]["nodeId"] == tree.root().nodeId()
        records[1]["actionType"] == "TOOL"
        records[1]["parentId"] == tree.root().nodeId()
    }

    def "ThinkingTree reset should clear all but root"() {
        given:
        def tree = new ThinkingTree([:])
        tree.expand(tree.root(), [
            { ThinkingNode p -> ThinkingNode.builder().actionType("LLM").actionTarget("test").build() } as Function
        ])

        expect:
        tree.nodeCount() == 2

        when:
        tree.reset()

        then:
        tree.nodeCount() == 1
        tree.getChildren(tree.root()).isEmpty()
        tree.allNodes().size() == 1
        tree.depth() == 0
    }

    def "ThinkingTree setTokenBudget with null should throw"() {
        given:
        def tree = new ThinkingTree([:])

        when: tree.setTokenBudget(null)
        then: thrown(NullPointerException)
    }

    def "ThinkingTree setTokenBudget should replace budget"() {
        given:
        def tree = new ThinkingTree([:])
        def budget = TokenBudget.of(5, 10)

        when:
        tree.setTokenBudget(budget)

        then:
        tree.tokenBudget() == budget
    }

    def "ThinkingTree mctsIteration with exhausted budget should not expand"() {
        given:
        def tree = new ThinkingTree([:])
        // Create a budget that exhausts after 0 tokens (use of(1,1) then consume to exhaust)
        def budget = TokenBudget.of(1, 100)
        tree.setTokenBudget(budget)
        // Consume the single token so budget is exhausted
        budget.consume(ThinkingNode.builder().build())

        when:
        tree.mctsIteration(1, 10,
            { ThinkingNode n -> [ThinkingNode.builder().actionType("A").actionTarget("T").build()] } as Function,
            { Map s -> 1.0d } as Function)

        then:
        tree.nodeCount() == 1  // no expansion happened
    }

    // ========================================================================
    // ThinkingNode — 补充 Edge Cases
    // ========================================================================

    def "ThinkingNode snapshot should copy node properties"() {
        given:
        def original = ThinkingNode.builder()
            .state([key: "value"])
            .actionType("LLM_INFERENCE")
            .actionTarget("gpt-4o")
            .actionParams([temp: 0.7])
            .thought("Reasoning...")
            .reward(2.5)
            .visits(3)
            .observation("Done")
            .build()

        when:
        def copy = original.snapshot()

        then:
        copy.actionType() == original.actionType()
        copy.actionTarget() == original.actionTarget()
        copy.thought() == original.thought()
        copy.reward() == original.reward()
        copy.visits() == original.visits()
        copy.observation() == original.observation()
        copy.state() == original.state()
        copy.actionParams() == original.actionParams()
    }

    def "ThinkingNode toString should include node info"() {
        given:
        def node = ThinkingNode.builder()
            .actionType("TOOL_CALL")
            .actionTarget("search_web")
            .build()

        expect:
        node.toString().contains("TOOL_CALL")
        node.toString().contains("search_web")
        node.toString().contains("id=")
    }

    def "ThinkingNode isRoot and isLeaf correctly"() {
        given:
        def root = ThinkingNode.builder().build()
        def child = ThinkingNode.builder().parent(root).build()

        expect:
        root.isRoot()
        root.isLeaf()   // not expanded → leaf

        !child.isRoot()
        child.isLeaf()  // not expanded → leaf

        when:
        child.markExpanded()

        then:
        !child.isLeaf()  // after markExpanded, not leaf
    }

    def "ThinkingNode setReward should overwrite"() {
        given:
        def node = ThinkingNode.builder().reward(1.0).build()

        when:
        node.setReward(5.0)

        then:
        node.reward() == 5.0
    }

    def "ThinkingNode Builder with null params"() {
        when:
        def node = ThinkingNode.builder().build()

        then:
        node.state() == [:]
        node.actionParams() == [:]
        node.actionType() == null
        node.actionTarget() == null
        node.thought() == null
        node.observation() == null
        node.reward() == 0.0
        node.visits() == 0
    }

    def "ThinkingNode uct for unvisited returns MAX_VALUE"() {
        given:
        def node = ThinkingNode.builder().build()

        expect:
        node.uct(10, Math.sqrt(2)) == Double.MAX_VALUE
    }

    // ========================================================================
    // FastPathStrategy — 补充 Edge Cases
    // ========================================================================

    def "FastPathStrategy should handle tool context"() {
        given:
        def supplier = Stub(org.cland.alice.core.planner.model.PlannerModelSupplier) {
            getInstructionModel() >> ModelSession.of("gpt-4o-mini", "test")
        }
        def strategy = new FastPathStrategy(supplier)

        when:
        def plan = strategy.decide([prompt: "Search something", availableTools: ["search_web"]])

        then:
        plan.type() == Plan.Type.FAST_PATH
        plan.steps().size() == 2
        plan.steps()[0].actionType() == "LLM_INFERENCE"
    }

    // ========================================================================
    // PlannerService — 补充 Edge Cases
    // ========================================================================

    def "PlannerService plan with null strategy should handle gracefully"() {
        given:
        // Must provide all builder dependencies to avoid NPE
        def fastPath = Stub(DecisionStrategy) {
            decide(_) >> Plan.fastPath("fallback", "FINISH", "FINISH")
        }
        def slowPath = Stub(DecisionStrategy)
        def selector = StrategySelector.builder()
            .fastPath(fastPath)
            .slowPath(slowPath)
            .build()
        def plannerService = PlannerService.builder()
            .strategySelector(selector)
            .build()

        when:
        def plan = plannerService.plan([:])

        then:
        plan != null
    }

    def "PlannerService should handle empty context"() {
        given:
        def supplier = Stub(org.cland.alice.core.planner.model.PlannerModelSupplier) {
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
        def plan = plannerService.plan([prompt: ""])

        then:
        plan != null
    }

    // ========================================================================
    // ModelSession — 补充 Edge Cases
    // ========================================================================

    def "ModelSession without parameters should have empty params"() {
        given:
        def session = ModelSession.of("gpt-4o", "test")

        expect:
        session.parameters() == [:]
    }

    def "ModelSession call completes correctly"() {
        given:
        def session = ModelSession.of("gpt-4o", "test")

        expect:
        session.call().status() == CallStatus.CREATED

        when:
        session.complete("result")

        then:
        session.call().status() == CallStatus.FINISHED
    }

    def "ModelSession call fails correctly"() {
        given:
        def session = ModelSession.of("gpt-4o", "test")

        expect:
        session.call().status() == CallStatus.CREATED

        when:
        session.fail(new RuntimeException("err"))

        then:
        session.call().status() == CallStatus.ABORTED
    }

    def "ModelSession error before complete returns null"() {
        given:
        def session = ModelSession.of("gpt-4o", "test")

        expect:
        session.error() == null
        session.response() == null
    }

    // ========================================================================
    // ModelCapabilities — fromCapability branch coverage
    // ========================================================================

    def "ModelCapabilities fromCapability with combined capabilities"() {
        expect:
        ModelCapabilities.fromCapability(
            org.cland.alice.model.Model.Capability.ALL) == ModelCapabilities.ALL
        ModelCapabilities.fromCapability(
            org.cland.alice.model.Model.Capability.NONE) == ModelCapabilities.NONE
        ModelCapabilities.fromCapability(
            org.cland.alice.model.Model.Capability.FUNCTION_CALL) == ModelCapabilities.FUNCTION_CALL
        ModelCapabilities.fromCapability(
            org.cland.alice.model.Model.Capability.STREAMING) == ModelCapabilities.STREAMING
        ModelCapabilities.fromCapability(
            org.cland.alice.model.Model.Capability.VISION) == ModelCapabilities.VISION
    }



    // ========================================================================
    // SlowPathStrategy — Builder + fallback branch
    // ========================================================================

    def "SlowPathStrategy builder with null tree should throw"() {
        when:
        SlowPathStrategy.builder().build()

        then:
        thrown(IllegalStateException)
    }

    def "SlowPathStrategy decide with no best child should fallback to LLM"() {
        given:
        def tree = new ThinkingTree([prompt: "test"])
        def supplier = Stub(PlannerModelSupplier) {
            getReasoningModel() >> ModelSession.of("gpt-4o", "test")
        }
        def strategy = SlowPathStrategy.builder()
            .tree(tree)
            .modelSupplier(supplier)
            .mctsIterations(0)  // no MCTS = no children
            .build()

        when:
        def plan = strategy.decide([prompt: "Some complex task without tools"])

        then:
        plan.type() == Plan.Type.SLOW_PATH
        plan.steps().size() == 2
        // Fallback: LLM_INFERENCE + FINISH
        plan.steps()[0].actionType() == "LLM_INFERENCE"
        plan.steps()[-1].actionType() == "FINISH"
        plan.metadata()["path"] == "slow"
        plan.metadata()["treeNodes"] >= 1
    }

    def "SlowPathStrategy decide with result should finish immediately"() {
        given:
        def tree = new ThinkingTree([prompt: "test"])
        def supplier = Stub(PlannerModelSupplier)
        def strategy = SlowPathStrategy.builder()
            .tree(tree)
            .modelSupplier(supplier)
            .build()

        when:
        def plan = strategy.decide([prompt: "test", result: "already done"])

        then:
        plan.type() == Plan.Type.SLOW_PATH
        plan.steps().size() == 1
        plan.steps()[0].actionType() == "FINISH"
        plan.metadata()["treeNodes"] >= 1
    }

    // ========================================================================
    // FastPathStrategy — null session branch
    // ========================================================================

    def "FastPathStrategy with null modelSupplier should throw"() {
        when:
        new FastPathStrategy(null)

        then:
        thrown(NullPointerException)
    }

    def "FastPathStrategy with result finishes immediately"() {
        given:
        def supplier = Stub(PlannerModelSupplier)
        def strategy = new FastPathStrategy(supplier)

        when:
        def plan = strategy.decide([prompt: "test", result: "done"])

        then:
        plan.steps().size() == 1
        plan.steps()[0].actionType() == "FINISH"
    }

    def "FastPathStrategy with empty prompt"() {
        given:
        def supplier = Stub(PlannerModelSupplier) {
            getInstructionModel() >> ModelSession.of("gpt-4o-mini", "")
        }
        def strategy = new FastPathStrategy(supplier)

        when:
        def plan = strategy.decide([prompt: ""])

        then:
        plan.type() == Plan.Type.FAST_PATH
        plan.steps().size() == 2
        plan.steps()[0].actionType() == "LLM_INFERENCE"
        plan.metadata()["path"] == "fast"
    }

    // ========================================================================
    // StrategySelector — edge cases
    // ========================================================================


}
