package org.cland.alice.core.agent;

import java.util.Map;
import org.cland.alice.core.agent.executor.AgentExecutor;
import org.cland.alice.core.agent.lifecycle.Action;
import org.cland.alice.core.agent.result.StepResult;

/**
 * Hole test entry point for alice-core-agent.
 *
 * <p>Exercises module boundary (AgentContext, StepResult, Action, AgentExecutor) directly, without
 * going through Gradle unit tests.
 *
 * <p>Usage (via Gradle): ./gradlew :alice-core-agent:runHoleTest --args="<key>"
 *
 * <p>Supported keys: context, stepResult, action, executor, all
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
      case "all" -> {
        testAgentContext();
        testStepResultSealed();
        testActionBuilder();
        testAgentExecutor();
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
