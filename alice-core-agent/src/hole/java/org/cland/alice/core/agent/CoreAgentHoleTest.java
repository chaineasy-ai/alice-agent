package org.cland.alice.core.agent;

import java.util.Map;
import org.cland.alice.core.agent.executor.AgentExecutor;
import org.cland.alice.core.agent.lifecycle.Action;
import org.cland.alice.core.agent.lifecycle.PlanToIntentConverter;
import org.cland.alice.core.agent.result.StepResult;
import org.cland.alice.core.planner.Plan;

/**
 * Hole test entry point for alice-core-agent.
 *
 * <p>Exercises module boundary (AgentContext, StepResult, Action, AgentExecutor) directly, without
 * going through Gradle unit tests.
 *
 * <p>Usage (via Gradle): ./gradlew :alice-core-agent:runHoleTest --args="<key>"
 *
 * <p>Supported keys: context, stepResult, action, executor, intent, all
 *
 * <p>Exit 0 = PASS, 1 = FAIL.
 */
public class CoreAgentHoleTest {

  public static void main(String[] args) throws Exception {
    if (args.length == 0) {
      fail("Usage: <key>\n  context, stepResult, action, executor, all");
    }
    switch (args[0]) {
      case "context" -> testAgentContext();
      case "stepResult" -> testStepResultSealed();
      case "action" -> testActionBuilder();
      case "executor" -> testAgentExecutor();
      case "intent" -> testIntentCompositeWithModel();
      case "all" -> {
        testAgentContext();
        testStepResultSealed();
        testActionBuilder();
        testAgentExecutor();
        testIntentCompositeWithModel();
      }
      default -> fail("Unknown key: " + args[0]);
    }
  }

  // ==================== AGT-P01: AgentContext session lifecycle ====================

  static void testAgentContext() {
    AgentContext ctx = new AgentContext("test-session-1");

    assertTrue("session id set", "test-session-1".equals(ctx.sessionId()));
    assertEq("initial iteration", 0, ctx.iteration());
    assertEq("initial phase", AgentContext.Phase.START, ctx.currentPhase());

    ctx.transitionTo(AgentContext.Phase.PERCEIVING);
    ctx.transitionTo(AgentContext.Phase.PLANNING);
    assertEq("phase after transitions", AgentContext.Phase.PLANNING, ctx.currentPhase());

    ctx.put("key1", "value1");
    ctx.put("key2", 42);
    assertEq("string attribute", "value1", ctx.get("key1"));
    assertEq("int attribute", 42, (int) ctx.get("key2"));

    ctx.appendThought("First thought");
    ctx.appendThought("Second thought");
    assertTrue("thought chain has content", ctx.thoughtChain().contains("First thought"));

    AgentContext ctx2 = new AgentContext(5);
    assertEq("max iterations from constructor", 5, ctx2.maxIterations());

    System.out.println("PASS: AGT-P01 AgentContext session lifecycle");
  }

  // ==================== AGT-P02: StepResult sealed pattern match ====================

  static void testStepResultSealed() {
    Action nextAction =
        Action.builder()
            .type(Action.Type.TOOL_CALL)
            .target("database")
            .thought("query data")
            .build();
    StepResult.Continue continueResult = new StepResult.Continue(nextAction);
    assertTrue("Continue is StepResult", continueResult instanceof StepResult);
    assertEq("Continue action type", Action.Type.TOOL_CALL, continueResult.nextAction().type());
    assertEq("Continue action target", "database", continueResult.nextAction().target());

    StepResult.Finish finishResult = new StepResult.Finish("Task completed");
    assertTrue("Finish is StepResult", finishResult instanceof StepResult);
    assertEq("Finish answer", "Task completed", finishResult.answer());

    StepResult.Failure failureResult = new StepResult.Failure("Connection timeout");
    assertTrue("Failure is StepResult", failureResult instanceof StepResult);
    assertEq("Failure errorMessage", "Connection timeout", failureResult.errorMessage());

    String matchResult = matchStepResult(continueResult);
    assertEq("Continue matched", "CONTINUE", matchResult);
    matchResult = matchStepResult(finishResult);
    assertEq("Finish matched", "FINISH", matchResult);
    matchResult = matchStepResult(failureResult);
    assertEq("Failure matched", "FAILURE", matchResult);

    System.out.println("PASS: AGT-P02 StepResult sealed pattern match");
  }

  static String matchStepResult(StepResult result) {
    if (result instanceof StepResult.Continue c) return "CONTINUE";
    if (result instanceof StepResult.Finish f) return "FINISH";
    if (result instanceof StepResult.Failure f) return "FAILURE";
    return "UNKNOWN";
  }

  // ==================== AGT-P03: Action builder ====================

  static void testActionBuilder() {
    Action action =
        Action.builder()
            .type(Action.Type.TOOL_CALL)
            .target("/tmp/file")
            .thought("read the file")
            .parameter("format", "text")
            .parameter("maxLines", 100)
            .build();

    assertEq("action type", Action.Type.TOOL_CALL, action.type());
    assertEq("action target", "/tmp/file", action.target());
    assertEq("action thought", "read the file", action.thought());
    assertEq("param format", "text", action.parameters().get("format"));
    assertEq("param maxLines", 100, (int) action.parameters().get("maxLines"));

    Action minimal = Action.builder().type(Action.Type.FINISH).target("").build();
    assertEq("minimal type", Action.Type.FINISH, minimal.type());

    Action finish = Action.finish();
    assertEq("finish type", Action.Type.FINISH, finish.type());

    Action toolCall = Action.toolCall("web_search", Map.of("query", "test"));
    assertEq("toolCall type", Action.Type.TOOL_CALL, toolCall.type());
    assertEq("toolCall target", "web_search", toolCall.target());

    Action llm = Action.llmInference("gpt-4", "hello");
    assertEq("llm type", Action.Type.LLM_INFERENCE, llm.type());
    assertEq("llm target", "gpt-4", llm.target());

    Action revision = Action.revision("feedback");
    assertEq("revision type", Action.Type.REVISION, revision.type());

    assertTrue("actionId not null", action.actionId() != null);

    System.out.println("PASS: AGT-P03 Action builder");
  }

  // ==================== AGT-P04: AgentExecutor ====================

  static void testAgentExecutor() {
    assertTrue("DEFAULT_MAX_ITERATIONS > 0", AgentContext.DEFAULT_MAX_ITERATIONS > 0);

    Class<?> executorClass = AgentExecutor.class;
    assertTrue("AgentExecutor class loads", executorClass != null);

    System.out.println("PASS: AGT-P04 AgentExecutor");
  }

  // ==================== AGT-P05: Intent composite with model ====================

  static void testIntentCompositeWithModel() {
    // --- TC-INTENT-01: ANALYZE intent → LLM_INFERENCE Action with model target ---
    {
      Plan plan = Plan.fastPath("Analyze the data", Plan.Intent.ANALYZE, "deepseek-v4-flash");
      Map<String, Object> intent =
          PlanToIntentConverter.planToIntent(plan, Map.of("prompt", "analyze this"));
      assertEq("ANALYZE intent type", "LLM_INFERENCE", intent.get("type"));
      assertEq("ANALYZE intent target (model)", "deepseek-v4-flash", intent.get("target"));
      assertTrue("ANALYZE intent has prompt", intent.containsKey("prompt"));

      Action action = PlanToIntentConverter.mapToAction(intent);
      assertEq("ANALYZE Action type", Action.Type.LLM_INFERENCE, action.type());
      assertEq("ANALYZE Action model target", "deepseek-v4-flash", action.target());
      System.out.println("  ✅ [INTENT-01] ANALYZE → LLM_INFERENCE with model target");
    }

    // --- TC-INTENT-02: SEARCH intent → TOOL_CALL Action ---
    {
      Plan plan = Plan.fastPath("Search web", Plan.Intent.SEARCH, "web_search");
      Map<String, Object> intent = PlanToIntentConverter.planToIntent(plan, Map.of());
      assertEq("SEARCH intent type", "TOOL_CALL", intent.get("type"));
      assertEq("SEARCH intent target (tool)", "web_search", intent.get("target"));

      Action action = PlanToIntentConverter.mapToAction(intent);
      assertEq("SEARCH Action type", Action.Type.TOOL_CALL, action.type());
      assertEq("SEARCH Action tool target", "web_search", action.target());
      System.out.println("  ✅ [INTENT-02] SEARCH → TOOL_CALL with tool target");
    }

    // --- TC-INTENT-03: ANSWER intent → FINISH Action ---
    {
      Plan plan = Plan.fastPath("Answer directly", Plan.Intent.ANSWER, "FINISH");
      Map<String, Object> intent = PlanToIntentConverter.planToIntent(plan, Map.of());
      assertEq("ANSWER intent type", "FINISH", intent.get("type"));

      Action action = PlanToIntentConverter.mapToAction(intent);
      assertEq("ANSWER Action type", Action.Type.FINISH, action.type());
      System.out.println("  ✅ [INTENT-03] ANSWER → FINISH");
    }

    // --- TC-INTENT-04: FINISH intent → FINISH Action ---
    {
      Plan plan = Plan.fastPath("Done", Plan.Intent.FINISH, "FINISH");
      Map<String, Object> intent = PlanToIntentConverter.planToIntent(plan, Map.of());
      assertEq("FINISH intent type", "FINISH", intent.get("type"));

      Action action = PlanToIntentConverter.mapToAction(intent);
      assertEq("FINISH Action type", Action.Type.FINISH, action.type());
      System.out.println("  ✅ [INTENT-04] FINISH → FINISH");
    }

    // --- TC-INTENT-05: CODE intent → LLM_INFERENCE with model target ---
    {
      Plan plan =
          Plan.builder()
              .type(Plan.Type.FAST_PATH)
              .summary("Write code")
              .addStep(Plan.Intent.CODE, "gpt-4o")
              .build();
      Map<String, Object> intent =
          PlanToIntentConverter.planToIntent(plan, Map.of("prompt", "write a function"));
      assertEq("CODE intent type", "LLM_INFERENCE", intent.get("type"));
      assertEq("CODE intent model target", "gpt-4o", intent.get("target"));

      Action action = PlanToIntentConverter.mapToAction(intent);
      assertEq("CODE Action type", Action.Type.LLM_INFERENCE, action.type());
      assertEq("CODE Action model", "gpt-4o", action.target());
      System.out.println("  ✅ [INTENT-05] CODE → LLM_INFERENCE with model target");
    }

    // --- TC-INTENT-06: GENERATE intent → LLM_INFERENCE with default model fallback ---
    {
      // When Plan step has no specific model target, defaults to "gpt-4o-mini"
      Plan plan =
          Plan.builder()
              .type(Plan.Type.FAST_PATH)
              .summary("Generate content")
              .addStep(Plan.Intent.GENERATE, null)
              .build();
      Map<String, Object> intent =
          PlanToIntentConverter.planToIntent(plan, Map.of("prompt", "write a poem"));
      assertEq("GENERATE intent type", "LLM_INFERENCE", intent.get("type"));
      assertEq("GENERATE fallback model", "gpt-4o-mini", intent.get("target"));

      Action action = PlanToIntentConverter.mapToAction(intent);
      assertEq("GENERATE Action fallback model", "gpt-4o-mini", action.target());
      System.out.println("  ✅ [INTENT-06] GENERATE → LLM_INFERENCE with default model");
    }

    // --- TC-INTENT-07: REVISION intent → REVISION Action with feedback ---
    {
      Plan plan =
          Plan.builder()
              .type(Plan.Type.SLOW_PATH)
              .summary("Revise code")
              .addStep(
                  Plan.Intent.REVISION,
                  "REVISION",
                  Map.of("feedback", "Use better variable names"),
                  null)
              .build();
      Map<String, Object> intent = PlanToIntentConverter.planToIntent(plan, Map.of());
      assertEq("REVISION intent type", "REVISION", intent.get("type"));
      assertTrue("REVISION has feedback", intent.containsKey("feedback"));

      Action action = PlanToIntentConverter.mapToAction(intent);
      assertEq("REVISION Action type", Action.Type.REVISION, action.type());
      assertEq(
          "REVISION feedback param",
          "Use better variable names",
          action.parameters().get("feedback"));
      System.out.println("  ✅ [INTENT-07] REVISION → REVISION with feedback");
    }

    // --- TC-INTENT-08: Multi-step Plan → only first step taken ---
    {
      Plan plan =
          Plan.builder()
              .type(Plan.Type.SLOW_PATH)
              .summary("Multi-step task")
              .addStep(
                  Plan.Intent.ANALYZE,
                  "deepseek-v4-flash",
                  Map.of("prompt", "analyze logs"),
                  "Step 1: analyze")
              .addStep(Plan.Intent.CODE, "gpt-4o", Map.of("prompt", "fix bug"), "Step 2: code fix")
              .addStep(Plan.Intent.FINISH, "FINISH")
              .build();
      Map<String, Object> intent = PlanToIntentConverter.planToIntent(plan, Map.of());
      // Only first step (ANALYZE) should be taken
      assertEq("Multi-step intent type", "LLM_INFERENCE", intent.get("type"));
      assertEq(
          "Multi-step intent target (first step model)", "deepseek-v4-flash", intent.get("target"));
      assertTrue("Multi-step has thought from first step", intent.containsKey("thought"));
      assertEq("Multi-step thought content", "Step 1: analyze", intent.get("thought"));
      System.out.println("  ✅ [INTENT-08] Multi-step Plan → only first step converted");
    }

    // --- TC-INTENT-09: Empty Plan → default LLM_INFERENCE with fallback model ---
    {
      Plan emptyPlan = Plan.builder().type(Plan.Type.FAST_PATH).summary("empty").build();
      // Empty steps list → planToIntent returns default LLM_INFERENCE
      Map<String, Object> intent =
          PlanToIntentConverter.planToIntent(emptyPlan, Map.of("prompt", "hello"));
      assertEq("Empty plan intent type", "LLM_INFERENCE", intent.get("type"));
      assertEq("Empty plan fallback target", "gpt-4o-mini", intent.get("target"));
      assertEq("Empty plan prompt passthrough", "hello", intent.get("prompt"));
      System.out.println("  ✅ [INTENT-09] Empty Plan → default LLM_INFERENCE");
    }

    System.out.println("PASS: AGT-P05 Intent composite with model (9 scenarios)");
  }

  // ==================== Assertion helpers ====================

  static void fail(String msg) {
    System.err.println("FAIL: " + msg);
    System.exit(1);
  }

  static void assertTrue(String label, boolean condition) {
    if (!condition) fail(label + " expected true");
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

  static void assertEq(String label, Action.Type expected, Action.Type actual) {
    if (expected != actual) {
      fail(label + " expected <" + expected + "> but got <" + actual + ">");
    }
  }

  static void assertEq(String label, AgentContext.Phase expected, AgentContext.Phase actual) {
    if (expected != actual) {
      fail(label + " expected <" + expected + "> but got <" + actual + ">");
    }
  }
}
