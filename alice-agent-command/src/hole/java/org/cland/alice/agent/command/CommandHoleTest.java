/*
 * Alice Agent — Command Module Hole Tests
 *
 * Dedicated module boundary probes, NOT unit tests.
 * Invoked via Gradle JavaExec (runHoleTest task).
 *
 * Each hole tests exactly ONE happy inbound business case at the module boundary.
 * NO edge cases (null, empty, invalid) — those belong in src/test/groovy.
 * NO duplication of existing Spock unit tests.
 *
 * Log format:
 *   CMD-P0X: ITEM <index>/<total> :: <description> :: OK|FAIL(<reason>)
 *   CMD-P0X: PASS :: <summary>
 *   CMD-P0X: FAIL :: <summary>
 *
 * ===========================================================================
 * Current hole test inventory:
 *
 * | Hole  | Description                                      | Items |
 * |-------|--------------------------------------------------|-------|
 * | CMD-P01 | Module classpath resolves all sealed types      |    21 |
 *          (Class.forName on all 21 concrete records via module path)
 * | CMD-P02 | AgentCommand.parse() dispatch pipeline end-to-end |     6 |
 *          (1 per branch: Exec, Cap, Align, Ctrl, Routine, SubAgent)
 *          NON-DUPLICATE: exercises the full JavaExec classpath resolution
 *          + module system sealing in a monolithic JVM invocation —
 *          something no single Spock test does.
 * ===========================================================================
 */

package org.cland.alice.agent.command;

/**
 * Hole test entry point for the {@code alice-agent-command} module.
 *
 * <p>Invoked via {@code ./gradlew :alice-agent-command:runHoleTest --args "<hole-id>"}.
 */
public final class CommandHoleTest {

  static final String SESSION = "hole-session";
  static final String TRACE = "hole-trace";

  private CommandHoleTest() {}

  // ========================================================================
  // Structured logging
  // ========================================================================

  static boolean anyFailed = false;

  static void itemOk(String holeId, int index, int total, String description) {
    System.out.println(holeId + ": ITEM " + index + "/" + total + " :: " + description + " :: OK");
  }

  static void itemFail(String holeId, int index, int total, String description, String reason) {
    System.err.println(
        holeId
            + ": ITEM "
            + index
            + "/"
            + total
            + " :: "
            + description
            + " :: FAIL("
            + reason
            + ")");
    anyFailed = true;
  }

  static void pass(String holeId, String msg) {
    System.out.println(holeId + ": PASS :: " + msg);
  }

  static void fail(String holeId, String msg) {
    System.err.println(holeId + ": FAIL :: " + msg);
    anyFailed = true;
  }

  static void exitIfFailed() {
    if (anyFailed) {
      System.err.println("HOLE: FAIL :: one or more items failed");
      System.exit(1);
    }
  }

  // ========================================================================
  // CMD-P01: Module classpath resolves all 21 sealed types
  //
  //   What it probes: Can the JVM module system / classloader find and
  //   load every concrete AgentCommand record class via Class.forName()?
  //
  //   Why this is NOT a unit test:
  //   - Spock tests construct records directly, never exercising
  //     Class.forName() or module system reflection.
  //   - This verifies the sealed hierarchy is fully visible on the
  //     module path at runtime — a true module boundary concern.
  //   - Failure modes: missing module exports, split packages,
  //     classpath vs module path conflicts.
  //
  //   Business case: "All command types are deployable as a sealed unit"
  // ========================================================================

  static void probeCmdP01() {
    anyFailed = false;
    final String H = "CMD-P01";
    int total = 21;

    // Fully-qualified class names of all 21 concrete records
    var classNames =
        new String[] {
          "org.cland.alice.agent.command.ExecutionCmd$AcquireGoalCmd",
          "org.cland.alice.agent.command.ExecutionCmd$ExecuteRawCmd",
          "org.cland.alice.agent.command.CapabilityCmd$RegisterSkillCmd",
          "org.cland.alice.agent.command.CapabilityCmd$UpdateRulesCmd",
          "org.cland.alice.agent.command.CapabilityCmd$ReloadKernelCmd",
          "org.cland.alice.agent.command.AlignmentCmd$SwitchModelCmd",
          "org.cland.alice.agent.command.ControlCmd$ResetSessionCmd",
          "org.cland.alice.agent.command.ControlCmd$FeedbackCmd",
          "org.cland.alice.agent.command.ControlCmd$InterruptCmd",
          "org.cland.alice.agent.command.ControlCmd$ClearContextCmd",
          "org.cland.alice.agent.command.ControlCmd$ViewContextCmd",
          "org.cland.alice.agent.command.ControlCmd$CompactContextCmd",
          "org.cland.alice.agent.command.RoutineTimeCmd$RegisterRoutineCmd",
          "org.cland.alice.agent.command.RoutineTimeCmd$TimeTriggeredCmd",
          "org.cland.alice.agent.command.SpawnSubAgentCmd",
          "org.cland.alice.agent.command.ConnectSubAgentCmd",
          "org.cland.alice.agent.command.ListSubAgentsCmd",
          "org.cland.alice.agent.command.CancelSubAgentCmd",
          "org.cland.alice.agent.command.GetSubAgentResultsCmd",
          "org.cland.alice.agent.command.SendToSubAgentCmd",
          "org.cland.alice.agent.command.PromptSubAgentCmd",
        };

    for (int i = 0; i < total; i++) {
      String fqcn = classNames[i];
      try {
        Class<?> clazz = Class.forName(fqcn);
        boolean isAgentCommand = AgentCommand.class.isAssignableFrom(clazz);
        if (isAgentCommand) {
          itemOk(H, i + 1, total, fqcn + " resolves & instanceof AgentCommand");
        } else {
          itemFail(H, i + 1, total, fqcn, "loaded but not instanceof AgentCommand");
        }
      } catch (ClassNotFoundException e) {
        itemFail(H, i + 1, total, fqcn, "ClassNotFoundException: " + e.getMessage());
      }
    }

    if (!anyFailed) {
      pass(H, "all " + total + " concrete record classes resolve on module path");
    }
    exitIfFailed();
  }

  // ========================================================================
  // CMD-P02: AgentCommand.parse() dispatch pipeline end-to-end
  //
  //   What it probes: Can we invoke AgentCommand.parse() via reflection
  //   (or direct call in a standalone JVM) and get a non-null result
  //   for every command branch?
  //
  //   Why this is NOT a unit test:
  //   - This exercises the full JavaExec runtime: module path resolution,
  //     class loading, static initializers, and the sealed interface
  //     pattern-matching dispatch in a single monolithic JVM session.
  //   - Spock tests run inside a test framework with different classloading.
  //   - This proves the dispatch works when the module is loaded as a
  //     runtime dependency (e.g., by the bootstrap module).
  //
  //   Business case: "AgentCommand.parse() dispatches correctly when
  //   loaded as a module dependency, not just inside Gradle test runner"
  //
  //   Coverage:
  //     1 per branch (not all 17 permutations — those are in Spock):
  //     ExecutionCmd, CapabilityCmd, AlignmentCmd, ControlCmd,
  //     RoutineTimeCmd, SubAgentCmd
  // ========================================================================

  static void probeCmdP02() {
    anyFailed = false;
    final String H = "CMD-P02";
    int total = 6;

    record ParseCase(String input, String expectedSimpleName) {}

    var cases =
        new ParseCase[] {
          new ParseCase("/run analyze logs", "AcquireGoalCmd"),
          new ParseCase("/reload", "ReloadKernelCmd"),
          new ParseCase("/model gpt-4", "SwitchModelCmd"),
          new ParseCase("/new", "ResetSessionCmd"),
          new ParseCase("/routine 0 */2 * * * ?", "RegisterRoutineCmd"),
          new ParseCase("/sub-agent list", "ListSubAgentsCmd"),
        };

    for (int i = 0; i < total; i++) {
      var c = cases[i];
      AgentCommand result = AgentCommand.parse(c.input(), SESSION, TRACE);
      boolean ok =
          result != null && result.getClass().getSimpleName().equals(c.expectedSimpleName());
      if (ok) {
        itemOk(H, i + 1, total, c.input() + " → " + c.expectedSimpleName());
      } else {
        String actualType = (result == null) ? "null" : result.getClass().getSimpleName();
        itemFail(H, i + 1, total, c.input() + " → " + c.expectedSimpleName(), "got " + actualType);
      }
    }

    if (!anyFailed) {
      pass(H, "all " + total + " command branches dispatch correctly via parse()");
    }
    exitIfFailed();
  }

  // ========================================================================
  // Main entry — dispatches by hole ID
  // ========================================================================

  public static void main(String[] args) {
    if (args.length == 0) {
      System.err.println("Usage: CommandHoleTest <hole-id>");
      System.err.println("  Hole IDs: CMD-P01, CMD-P02");
      System.exit(1);
    }

    switch (args[0]) {
      case "CMD-P01" -> probeCmdP01();
      case "CMD-P02" -> probeCmdP02();
      default -> {
        System.err.println("Unknown hole ID: " + args[0]);
        System.exit(1);
      }
    }
  }
}
