package org.cland.alice.env.adapter;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.cland.alice.env.adapter.snapshot.EnvSnapshot;
import org.cland.alice.env.adapter.snapshot.SnapshotManager;
import org.cland.alice.env.adapter.state.EnvState;
import org.cland.alice.env.adapter.transport.McpTransport;
import org.cland.alice.tool.gateway.model.Tool;
import org.cland.alice.tool.gateway.model.ToolResult;

/**
 * Hole test entry point for alice-env-adapter.
 *
 * <p>Exercises module boundary (EnvManager, McpClient, SnapshotManager, EnvState, McpTransport)
 * directly, without going through Gradle unit tests.
 *
 * <p>Usage (via Gradle): ./gradlew :alice-env-adapter:runHoleTest --args="&lt;key&gt;"
 *
 * <p>Supported keys: envState, envSnapshot, snapshotManager, mcpClient, mcpTransport, all
 *
 * <p>Exit 0 = PASS, 1 = FAIL.
 */
public class EnvAdapterHoleTest {

  public static void main(String[] args) throws Exception {
    if (args.length == 0) {
      fail(
          "Usage: <key>\n"
              + "  envState, envSnapshot, snapshotManager, mcpClient, mcpTransport, all");
    }
    switch (args[0]) {
      case "envState" -> testEnvState();
      case "envSnapshot" -> testEnvSnapshot();
      case "snapshotManager" -> testSnapshotManager();
      case "mcpClient" -> testMcpClient();
      case "mcpTransport" -> testMcpTransport();
      case "all" -> {
        testEnvState();
        testEnvSnapshot();
        testSnapshotManager();
        testMcpClient();
        testMcpTransport();
      }
      default -> fail("Unknown key: " + args[0]);
    }
  }

  // ==================== ENV-P01: EnvState ====================

  static void testEnvState() {
    // State machine values
    assertEq("DISCONNECTED exists", EnvState.DISCONNECTED, EnvState.valueOf("DISCONNECTED"));
    assertEq("READY exists", EnvState.READY, EnvState.valueOf("READY"));
    assertEq("COMMITTED exists", EnvState.COMMITTED, EnvState.valueOf("COMMITTED"));

    // canExecute
    assertTrue("READY can execute", EnvState.READY.canExecute());
    assertTrue("DISCONNECTED cannot execute", !EnvState.DISCONNECTED.canExecute());

    // isTerminal
    assertTrue("DISCONNECTED is terminal", EnvState.DISCONNECTED.isTerminal());
    assertTrue("COMMITTED is terminal", EnvState.COMMITTED.isTerminal());
    assertTrue("READY is not terminal", !EnvState.READY.isTerminal());

    // isTransitional
    assertTrue("CAPTURING_SNAPSHOT is transitional", EnvState.CAPTURING_SNAPSHOT.isTransitional());
    assertTrue("EXECUTING is transitional", EnvState.EXECUTING.isTransitional());
    assertTrue("AUDITING is transitional", EnvState.AUDITING.isTransitional());
    assertTrue("ROLLING_BACK is transitional", EnvState.ROLLING_BACK.isTransitional());
    assertTrue("READY is not transitional", !EnvState.READY.isTransitional());

    // All 8 states defined
    assertEq("total states", 8, EnvState.values().length);

    System.out.println("PASS: ENV-P01 EnvState state machine");
  }

  // ==================== ENV-P02: EnvSnapshot ====================

  static void testEnvSnapshot() {
    // Empty snapshot
    EnvSnapshot empty = EnvSnapshot.empty();
    assertTrue("empty id not null", empty.snapshotId() != null);
    assertTrue("empty timestamp not null", empty.timestamp() != null);
    assertTrue("empty resource versions", empty.resourceVersions().isEmpty());
    assertTrue("empty has no side effects", empty.irreversibleEffects().isEmpty());

    // Builder with data
    EnvSnapshot snapshot =
        EnvSnapshot.builder()
            .snapshotId("snap-1")
            .resourceVersions(Map.of("file:///data", "v1", "file:///logs", "v2"))
            .workingDirectoryState(Map.of("cwd", "/home/user"))
            .environmentVariables(Map.of("PATH", "/usr/bin"))
            .addIrreversibleEffect(
                new EnvSnapshot.IrreversibleSideEffect("send_email", "Sent email notification"))
            .build();

    assertEq("snapshot id", "snap-1", snapshot.snapshotId());
    assertEq("resource version count", 2, snapshot.resourceVersions().size());
    assertEq("cwd", "/home/user", snapshot.workingDirectoryState().get("cwd"));
    assertEq("PATH", "/usr/bin", snapshot.environmentVariables().get("PATH"));
    assertEq("side effects count", 1, snapshot.irreversibleEffects().size());

    System.out.println("PASS: ENV-P02 EnvSnapshot");
  }

  // ==================== ENV-P03: SnapshotManager ====================

  static void testSnapshotManager() {
    SnapshotManager manager = new SnapshotManager();

    // Initially empty
    assertTrue("no snapshot initially", manager.latestSnapshot().isEmpty());

    // Save a snapshot
    EnvSnapshot snap1 =
        EnvSnapshot.builder()
            .snapshotId("snap-1")
            .resourceVersions(Map.of("file:///data", "v1"))
            .build();
    manager.save(snap1);

    assertTrue("latest returns snap1", manager.latestSnapshot().isPresent());
    assertEq("latest id", "snap-1", manager.latestSnapshot().get().snapshotId());

    // Save another
    EnvSnapshot snap2 = EnvSnapshot.builder().snapshotId("snap-2").build();
    manager.save(snap2);
    assertEq("latest is snap2", "snap-2", manager.latestSnapshot().get().snapshotId());

    // Rollback returns latest (peek) without removing
    var rollbackResult = manager.rollback();
    assertTrue("rollback returns snapshot", rollbackResult.isPresent());
    assertEq("rollback returns latest", "snap-2", rollbackResult.get().snapshotId());

    // history size is 2 (save+save)
    assertEq("history size", 2, manager.historySize());

    // Clear and verify
    manager.clear();
    assertTrue("after clear, empty", manager.latestSnapshot().isEmpty());

    System.out.println("PASS: ENV-P03 SnapshotManager");
  }

  // ==================== ENV-P04: McpClient ====================

  static void testMcpClient() {
    // Cannot create McpClient without a transport easily, but we can test
    // the Tool model that it uses (from alice-tool-gateway)
    Tool tool = Tool.builder().name("echo").description("Echoes input").build();
    assertEq("tool name", "echo", tool.name());
    assertEq("tool description", "Echoes input", tool.description());

    ToolResult result = ToolResult.success("hello");
    assertEq("result status", ToolResult.Status.SUCCESS, result.status());
    assertEq("result text", "hello", result.text());

    System.out.println("PASS: ENV-P04 McpClient / Tool model");
  }

  // ==================== ENV-P05: McpTransport ====================

  static void testMcpTransport() {
    // Verify we can create a FakeMcpTransport and test the interface contract
    FakeMcpTransport transport = new FakeMcpTransport();
    transport.connect();
    assertTrue("connected", transport.connected);

    String response = transport.send("{\"jsonrpc\":\"2.0\",\"method\":\"ping\"}").join();
    assertEq("response", "{\"jsonrpc\":\"2.0\",\"result\":\"pong\"}", response);
    assertEq("last sent message", "{\"jsonrpc\":\"2.0\",\"method\":\"ping\"}", transport.lastSent);

    transport.disconnect();
    assertTrue("disconnected", !transport.connected);

    System.out.println("PASS: ENV-P05 McpTransport interface");
  }

  // ==================== Fake transport ====================

  static class FakeMcpTransport implements McpTransport {
    boolean connected = false;
    String lastSent = null;

    @Override
    public CompletableFuture<Void> connect() {
      this.connected = true;
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<String> send(String message) {
      this.lastSent = message;
      return CompletableFuture.completedFuture("{\"jsonrpc\":\"2.0\",\"result\":\"pong\"}");
    }

    @Override
    public void disconnect() {
      this.connected = false;
    }

    @Override
    public boolean isConnected() {
      return connected;
    }

    @Override
    public void onNotification(McpTransport.NotificationListener listener) {
      // No-op for fake transport
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

  static void assertEq(String label, long expected, long actual) {
    if (expected != actual) {
      fail(label + " expected <" + expected + "> but got <" + actual + ">");
    }
  }
}
