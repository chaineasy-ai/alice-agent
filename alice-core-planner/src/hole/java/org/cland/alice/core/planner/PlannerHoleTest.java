package org.cland.alice.core.planner;

import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import org.cland.alice.core.planner.budget.TokenBudget;
import org.cland.alice.core.planner.sop.SopRegistry;
import org.cland.alice.core.planner.sop.StaticPlanner;
import org.cland.alice.core.planner.strategy.DecisionStrategy;
import org.cland.alice.core.planner.strategy.FastPathStrategy;
import org.cland.alice.core.planner.strategy.SlowPathStrategy;
import org.cland.alice.core.planner.strategy.StrategySelector;
import org.cland.alice.core.planner.tree.ThinkingNode;
import org.cland.alice.core.planner.tree.ThinkingTree;

/**
 * Hole test entry point for alice-core-planner.
 *
 * <p>Exercises module boundary (PlannerService → StrategySelector → FastPathStrategy /
 * SlowPathStrategy → Plan) directly, without going through Gradle unit tests.
 *
 * <p>Usage (via Gradle): ./gradlew :alice-core-planner:runHoleTest --args="&lt;key&gt;"
 *
 * <p>Supported keys: service, fast_path, slow_path, selector, budget, tree, static_planner, all
 *
 * <p>Exit 0 = PASS, 1 = FAIL.
 */
public class PlannerHoleTest {

  static final String PROJ_ROOT;

  static {
    String cwd = Paths.get("..").toAbsolutePath().normalize().toString();
    PROJ_ROOT = cwd.replace('\\', '/');
  }

  public static void main(String[] args) throws Exception {
    if (args.length == 0) {
      fail(
          "Usage: <key>\n"
              + "  service, fast_path, slow_path, selector, budget, tree, static_planner, all");
    }

    switch (args[0]) {
      case "service" -> testPlannerServicePlan();
      case "fast_path" -> testFastPathStrategy();
      case "slow_path" -> testSlowPathStrategy();
      case "selector" -> testStrategySelectorRouting();
      case "budget" -> testTokenBudget();
      case "tree" -> testThinkingTree();
      case "static_planner" -> testStaticPlanner();
      case "all" -> {
        testPlannerServicePlan();
        testFastPathStrategy();
        testSlowPathStrategy();
        testStrategySelectorRouting();
        testTokenBudget();
        testThinkingTree();
        testStaticPlanner();
      }
      default -> fail("Unknown key: " + args[0]);
    }
  }

  // ==================== PLN-P01: PlannerService.plan(Map) ====================

  static void testPlannerServicePlan() {
    // Use builder directly — no model supplier needed for static/SOP plans
    var selector =
        StrategySelector.builder()
            .fastPath(ctx -> Plan.fastPath("Fast", "FINISH", "FINISH"))
            .slowPath(
                ctx ->
                    Plan.builder()
                        .type(Plan.Type.SLOW_PATH)
                        .summary("Slow")
                        .addStep("FINISH", "FINISH")
                        .build())
            .build();

    var planner = PlannerService.builder().strategySelector(selector).build();

    // Simple prompt → FAST_PATH
    Plan fastPlan = planner.plan(Map.of("prompt", "hello"));
    assertEq("simple prompt type", Plan.Type.FAST_PATH, fastPlan.type());
    assertTrue("simple prompt has steps", fastPlan.steps().size() >= 1);

    // Complex prompt (override via custom complexity via context not possible with default
    // selector; this tests the selector we injected above routes based on complexityFunction)
    // For this test we just verify plan() works with the injected selector
    Plan plan = planner.plan(Map.of("prompt", "test"));
    assertTrue("plan has steps", plan.steps().size() >= 1);

    System.out.println("PASS: PLN-P01 PlannerService.plan()");
  }

  // ==================== PLN-P02: FastPathStrategy ====================

  static void testFastPathStrategy() {
    // FastPathStrategy requires a PlannerModelSupplier — we use a lightweight test
    // that constructs the strategy and verifies it produces FAST_PATH plan
    var strategy = new FastPathStrategy(new TestPlannerModelSupplier());

    Plan plan = strategy.decide(Map.of("prompt", "What is Java?"));
    assertEq("fast path type", Plan.Type.FAST_PATH, plan.type());
    assertTrue("fast path has steps", plan.steps().size() == 2);
    assertEq("first step is LLM_INFERENCE", "LLM_INFERENCE", plan.steps().get(0).actionType());
    assertEq("last step is FINISH", "FINISH", plan.steps().get(1).actionType());

    // If result is present, fast path returns immediate FINISH
    Plan finishPlan = strategy.decide(Map.of("prompt", "test", "result", "done"));
    assertEq("finish on result", "FINISH", finishPlan.steps().get(0).actionType());

    System.out.println("PASS: PLN-P02 FastPathStrategy.decide()");
  }

  // ==================== PLN-P03: SlowPathStrategy ====================

  static void testSlowPathStrategy() {
    var tree = new ThinkingTree(Map.of("prompt", "Complex multi-step analysis task"));
    var strategy =
        SlowPathStrategy.builder()
            .tree(tree)
            .modelSupplier(new TestPlannerModelSupplier())
            .mctsIterations(5)
            .build();

    Plan plan = strategy.decide(Map.of("prompt", "Complex multi-step analysis task"));
    assertEq("slow path type", Plan.Type.SLOW_PATH, plan.type());
    // Single next action: exactly 1 action step + FINISH = 2 steps
    assertEq("slow path step count (next action)", 2, plan.steps().size());
    assertEq("last step is FINISH", "FINISH", plan.steps().get(1).actionType());
    assertEq("meta path=slow", "slow", plan.metadata().get("path"));
    assertTrue("meta treeNodes > 0", (int) plan.metadata().get("treeNodes") > 0);
    // Tree summary metadata
    assertTrue("meta rootChildren present", plan.metadata().containsKey("rootChildren"));
    assertTrue("meta bestAction present", plan.metadata().containsKey("bestAction"));
    assertTrue("meta bestAvgReward present", plan.metadata().containsKey("bestAvgReward"));

    System.out.println("PASS: PLN-P03 SlowPathStrategy.decide()");
  }

  // ==================== PLN-P04: StrategySelector routing ====================

  static void testStrategySelectorRouting() {
    var fastPath = (DecisionStrategy) ctx -> Plan.fastPath("Fast", "FINISH", "FINISH");
    var slowPath =
        (DecisionStrategy)
            ctx ->
                Plan.builder()
                    .type(Plan.Type.SLOW_PATH)
                    .summary("Slow")
                    .addStep("FINISH", "FINISH")
                    .build();

    var selector = StrategySelector.builder().fastPath(fastPath).slowPath(slowPath).build();

    // Short prompt → FAST_PATH
    assertEq(
        "short prompt → fast",
        Plan.Type.FAST_PATH,
        selector.select(Map.of("prompt", "hello")).type());

    // Keyword → SLOW_PATH
    assertEq(
        "keyword → slow",
        Plan.Type.SLOW_PATH,
        selector.select(Map.of("prompt", "analyze this")).type());

    // Length > 200 → SLOW_PATH
    String longPrompt = "A".repeat(250);
    assertEq(
        "long prompt → slow",
        Plan.Type.SLOW_PATH,
        selector.select(Map.of("prompt", longPrompt)).type());

    // Has feedback → SLOW_PATH
    assertEq(
        "feedback → slow",
        Plan.Type.SLOW_PATH,
        selector.select(Map.of("prompt", "hello", "lastFeedback", "redo")).type());

    // Has error → SLOW_PATH
    assertEq(
        "error → slow",
        Plan.Type.SLOW_PATH,
        selector.select(Map.of("prompt", "hello", "error", "timeout")).type());

    System.out.println("PASS: PLN-P04 StrategySelector routing");
  }

  // ==================== PLN-P05: TokenBudget ====================

  static void testTokenBudget() {
    TokenBudget budget = TokenBudget.of(3, 10);
    assertFalse("not exhausted initially", budget.isExhausted());
    assertEq("max tokens", 3, budget.maxTokens());
    assertEq("consumed = 0", 0, budget.consumedTokens());

    ThinkingNode node = ThinkingNode.builder().build();
    budget.consume(node);
    budget.consume(node);
    assertEq("consumed = 2", 2, budget.consumedTokens());
    assertFalse("not exhausted at 2", budget.isExhausted());

    budget.consume(node);
    assertEq("consumed = 3", 3, budget.consumedTokens());
    assertTrue("exhausted at 3", budget.isExhausted());

    // Unlimited
    TokenBudget unlimited = TokenBudget.unlimited();
    assertTrue("unlimited", unlimited.isUnlimited());
    for (int i = 0; i < 100; i++) {
      unlimited.consume(node);
    }
    assertFalse("unlimited never exhausted", unlimited.isExhausted());

    System.out.println("PASS: PLN-P05 TokenBudget");
  }

  // ==================== PLN-P06: ThinkingTree ====================

  static void testThinkingTree() {
    ThinkingTree tree = new ThinkingTree(Map.of("prompt", "Hello"));
    assertEq("root actionType", "ROOT", tree.root().actionType());
    assertEq("node count 1", 1, tree.nodeCount());
    assertEq("depth 0", 0, tree.depth());

    // Expand
    tree.expand(
        tree.root(),
        List.of(
            p -> ThinkingNode.builder().actionType("LLM_INFERENCE").actionTarget("gpt-4o").build(),
            p -> ThinkingNode.builder().actionType("TOOL_CALL").actionTarget("search").build()));

    assertEq("node count 3", 3, tree.nodeCount());
    assertEq("depth 2", 2, tree.depth());

    // Backpropagate
    ThinkingNode child = tree.getChildren(tree.root()).get(0);
    tree.backpropagate(child, 2.0);
    assertEq("child reward", 2.0, child.reward(), 0.001);
    assertEq("child visits", 1, child.visits());
    assertEq("root reward", 2.0, tree.root().reward(), 0.001);
    assertEq("root visits", 1, tree.root().visits());

    // Best path
    List<ThinkingNode> path = tree.bestPath();
    assertTrue("best path non-empty", path.size() >= 1);
    assertTrue("best path starts at root", path.get(0).isRoot());

    System.out.println("PASS: PLN-P06 ThinkingTree");
  }

  // ==================== PLN-P07: StaticPlanner + SopRegistry ====================

  static void testStaticPlanner() {
    SopRegistry registry = new SopRegistry();
    registry.register(
        SopRegistry.SopTemplate.builder()
            .id("search_workflow")
            .keywords(List.of("search", "find", "lookup"))
            .addStep("TOOL_CALL", "search_web")
            .addStep("LLM_INFERENCE", "gpt-4o")
            .build());

    StaticPlanner planner = new StaticPlanner(registry);
    Plan plan = planner.plan(Map.of("prompt", "Please search for documents"));

    assertEq("static plan type", Plan.Type.STATIC, plan.type());
    assertTrue("static plan has steps", plan.steps().size() == 3);
    assertEq("first step tool", "TOOL_CALL", plan.steps().get(0).actionType());
    assertEq("first step target", "search_web", plan.steps().get(0).target());
    assertEq("sopId metadata", "search_workflow", plan.metadata().get("sopId"));

    System.out.println("PASS: PLN-P07 StaticPlanner + SopRegistry");
  }

  // ==================== Test model supplier ====================

  /** Minimal PlannerModelSupplier for hole tests — no actual model call. */
  static class TestPlannerModelSupplier
      implements org.cland.alice.core.planner.model.PlannerModelSupplier {

    @Override
    public org.cland.alice.core.planner.model.ModelSession getReasoningModel() {
      return org.cland.alice.core.planner.model.ModelSession.of("gpt-4o", "test");
    }

    @Override
    public org.cland.alice.core.planner.model.ModelSession getInstructionModel() {
      return org.cland.alice.core.planner.model.ModelSession.of("gpt-4o-mini", "test");
    }

    @Override
    public org.cland.alice.model.Call.Response request(org.cland.alice.model.Call call) {
      return null; // not called in these hole tests
    }
  }

  // ==================== Assertion helpers ====================

  static void fail(String msg) {
    System.err.println("FAIL: " + msg);
    System.exit(1);
  }

  static void assertTrue(String label, boolean condition) {
    if (!condition) fail(label + " expected true");
  }

  static void assertFalse(String label, boolean condition) {
    if (condition) fail(label + " expected false");
  }

  static void assertEq(String label, Object expected, Object actual) {
    if (!java.util.Objects.equals(expected, actual)) {
      fail(label + " expected <" + expected + "> but got <" + actual + ">");
    }
  }

  static void assertEq(String label, int expected, int actual) {
    if (expected != actual) {
      fail(label + " expected <" + expected + "> but got <" + actual + ">");
    }
  }

  static void assertEq(String label, double expected, double actual, double delta) {
    if (Math.abs(expected - actual) > delta) {
      fail(label + " expected <" + expected + "> but got <" + actual + ">");
    }
  }
}
