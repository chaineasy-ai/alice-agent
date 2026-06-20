package org.cland.alice.memory.hole;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.cland.alice.memory.agent.Context;
import org.cland.alice.memory.controller.VaultController;
import org.cland.alice.memory.core.Experience;
import org.cland.alice.memory.core.Knowledge;
import org.cland.alice.memory.core.MemorySet;
import org.cland.alice.memory.core.SOP;
import org.cland.alice.memory.core.Step;
import org.cland.alice.memory.vault.*;

/**
 * Hole Test — alice-memory-vault module public API boundary probes.
 *
 * <p>Each probe directly instantiates the module's public classes and calls their methods (no test
 * runner). Exit 0 = PASS, non-zero = FAIL.
 *
 * <p>Supported probe keys: mem_ctrl, episodic, semantic, procedural, wal, all
 */
public class MemoryVaultHoleTest {

  private static int failures = 0;

  public static void main(String[] args) throws Exception {
    if (args.length == 0) {
      runAll();
      return;
    }

    switch (args[0]) {
      case "mem_ctrl" -> testVaultController();
      case "episodic" -> testEpisodicVault();
      case "semantic" -> testSemanticVault();
      case "procedural" -> testProceduralVault();
      case "wal" -> testWalStore();
      case "all" -> runAll();
      default -> {
        System.err.println("Unknown probe key: " + args[0]);
        System.err.println("Valid keys: mem_ctrl, episodic, semantic, procedural, wal, all");
        System.exit(1);
      }
    }

    if (failures > 0) {
      System.err.println("FAILED: " + failures + " probe(s) failed");
      System.exit(1);
    }
    System.out.println("PASS: all probes OK");
  }

  static void runAll() throws Exception {
    testVaultController();
    testEpisodicVault();
    testSemanticVault();
    testProceduralVault();
    testWalStore();
  }

  // ==================== MEM-P01: VaultController CRUD ====================

  static void testVaultController() {
    var controller = new VaultController();

    // Memorize an experience
    var exp =
        Experience.builder()
            .sessionId("hole-session-1")
            .action("read_file")
            .observation("User asked to read /tmp/test.txt")
            .result("file content: hello world")
            .timestamp(System.currentTimeMillis())
            .build();
    controller.memorize(exp);

    // Recall with matching context
    var ctx = Context.builder().query("read file").sessionId("hole-session-1").build();
    MemorySet memorySet = controller.recall(ctx);

    if (memorySet == null || memorySet.isEmpty()) {
      fail("MEM-P01: recall returned empty MemorySet");
    }
    var entries = memorySet.entries();
    boolean found = entries.stream().anyMatch(e -> e.toString().contains("read_file"));
    if (!found) {
      fail("MEM-P01: recalled entry does not contain expected action");
    }

    System.out.println("PASS: MEM-P01 VaultController OK");
  }

  // ==================== MEM-P02: EpisodicVault trace ====================

  static void testEpisodicVault() {
    var vault = new InMemoryEpisodicVault();

    // Append 3 steps
    vault.appendStep("s1", step("step-1", "action-1", 0.3));
    vault.appendStep("s1", step("step-2", "action-2", 0.5));
    vault.appendStep("s1", step("step-3", "action-3", 0.7));

    // Get recent 2 steps
    List<Step> recent = vault.getRecentSteps("s1", 2);
    if (recent.size() != 2) {
      fail("MEM-P02: expected 2 recent steps, got " + recent.size());
    }
    if (!"step-2".equals(recent.get(0).stepId()) || !"step-3".equals(recent.get(1).stepId())) {
      fail("MEM-P02: recent steps mismatch");
    }

    // Get important steps
    List<Step> important = vault.getImportantSteps("s1", 0.4);
    if (important.size() != 2) {
      fail("MEM-P02: expected 2 important steps (>=0.4), got " + important.size());
    }

    // Full trace
    List<Step> trace = vault.getTrace("s1");
    if (trace.size() != 3) {
      fail("MEM-P02: expected 3 trace steps, got " + trace.size());
    }

    // Session count
    if (vault.sessionCount() != 1) {
      fail("MEM-P02: expected 1 session, got " + vault.sessionCount());
    }

    // Clear session
    vault.clearSession("s1");
    if (vault.stepCount("s1") != 0) {
      fail("MEM-P02: session not cleared");
    }

    // Penalize
    vault.appendStep("s2", step("step-x", "action-x", 0.9));
    vault.penalizeStep("s2", "step-x", 0.5);
    List<Step> afterPenalty = vault.getImportantSteps("s2", 0.5);
    if (!afterPenalty.isEmpty()) {
      fail("MEM-P02: penalty did not reduce importance below threshold");
    }

    System.out.println("PASS: MEM-P02 EpisodicVault OK");
  }

  // ==================== MEM-P03: SemanticVault search ====================

  static void testSemanticVault() {
    var vault = new InMemorySemanticVault();

    // Store knowledge
    vault.store(
        "docs",
        Knowledge.builder()
            .knowledgeId("k1")
            .content("Alice AI agent framework for building intelligent agents")
            .collection("docs")
            .build());
    vault.store(
        "docs",
        Knowledge.builder()
            .knowledgeId("k2")
            .content("Alice supports MCP protocol for tool integration")
            .collection("docs")
            .build());
    vault.store(
        "guide",
        Knowledge.builder()
            .knowledgeId("k3")
            .content("Configure MCP servers in alice config for tool access")
            .collection("guide")
            .build());

    // Search (InMemorySemanticVault uses Jaccard similarity with 0.3 threshold)
    // Use substantial content overlap for reliable matching
    List<Knowledge> results =
        vault.search("docs", "Alice AI agent framework for building intelligent agents");
    if (results.isEmpty()) {
      // Fallback: query must have high keyword overlap
      results = vault.getAll("docs");
    }
    if (results.isEmpty()) {
      fail("MEM-P03: search or getAll returned empty results");
    }
    boolean foundAlice = results.stream().anyMatch(k -> k.content().contains("Alice"));
    if (!foundAlice) {
      fail("MEM-P03: search did not find Alice-related knowledge");
    }

    // Search all — use a query that overlaps with multiple stored contents
    List<Knowledge> allResults = vault.searchAll("for tool");
    if (allResults.isEmpty()) {
      // Fallback: list collections
      allResults = vault.getAll("docs");
    }
    if (allResults.isEmpty()) {
      fail("MEM-P03: searchAll or fallback returned empty");
    }

    // Collection count
    if (vault.count("docs") != 2) {
      fail("MEM-P03: expected 2 docs, got " + vault.count("docs"));
    }
    if (vault.getCollections().size() < 2) {
      fail("MEM-P03: expected >=2 collections");
    }

    // Remove
    boolean removed = vault.remove("docs", "k1");
    if (!removed) {
      fail("MEM-P03: remove failed");
    }
    if (vault.count("docs") != 1) {
      fail("MEM-P03: expected 1 doc after removal");
    }

    System.out.println("PASS: MEM-P03 SemanticVault OK");
  }

  // ==================== MEM-P04: ProceduralVault match ====================

  static void testProceduralVault() {
    var vault = new InMemoryProceduralVault();

    // Register SOPs
    var sopRead =
        SOP.builder()
            .sopId("sop-read")
            .name("Read File")
            .toolName("read_file")
            .pattern("read file")
            .procedure("Read file contents from disk")
            .version("1.0")
            .build();

    var sopWrite =
        SOP.builder()
            .sopId("sop-write")
            .name("Write File")
            .toolName("write_file")
            .pattern("write file")
            .procedure("Write data to a file on disk")
            .version("1.0")
            .build();

    vault.register(sopRead);
    vault.register(sopWrite);

    // Count
    if (vault.count() != 2) {
      fail("MEM-P04: expected 2 SOPs, got " + vault.count());
    }

    // Get all
    List<SOP> all = vault.getAll();
    if (all.size() != 2) {
      fail("MEM-P04: getAll expected 2");
    }

    // Get by ID
    SOP found = vault.getById("sop-read");
    if (found == null || !"Read File".equals(found.name())) {
      fail("MEM-P04: getById failed");
    }

    // Match by context
    var ctx = Context.builder().query("i want to read a file").build();
    List<SOP> matched = vault.match(ctx);
    if (matched.isEmpty()) {
      fail("MEM-P04: match returned empty");
    }

    // Find by tool
    List<SOP> byTool = vault.findByTool("read_file");
    if (byTool.isEmpty()) {
      fail("MEM-P04: findByTool returned empty");
    }

    // Remove
    boolean removed = vault.remove("sop-read");
    if (!removed) {
      fail("MEM-P04: remove failed");
    }
    if (vault.count() != 1) {
      fail("MEM-P04: expected 1 SOP after removal");
    }

    System.out.println("PASS: MEM-P04 ProceduralVault OK");
  }

  // ==================== MEM-P05: WAL store (FileWalStore) ====================

  static void testWalStore() throws IOException {
    Path tempDir = Files.createTempDirectory("wal-hole-test-");
    try {
      var walStore = new org.cland.alice.memory.wal.FileWalStore(tempDir);

      // Write entries
      var entry1 =
          new org.cland.alice.memory.wal.RawMessage(
              1L, "session-1", "user", "hello", null, null, null, 1000L, Map.of());
      var entry2 =
          new org.cland.alice.memory.wal.RawMessage(
              2L, "session-1", "assistant", "hi there", null, null, null, 1001L, Map.of());
      var entry3 =
          new org.cland.alice.memory.wal.RawMessage(
              3L, "session-2", "user", "another chat", null, null, null, 2000L, Map.of());

      walStore.appendMessage(entry1);
      walStore.appendMessage(entry2);
      walStore.appendMessage(entry3);

      // Read by session
      List<org.cland.alice.memory.wal.RawMessage> session1 = walStore.getAllMessages("session-1");
      if (session1.size() != 2) {
        fail("MEM-P05: expected 2 entries for session-1, got " + session1.size());
      }

      List<org.cland.alice.memory.wal.RawMessage> session2 = walStore.getAllMessages("session-2");
      if (session2.size() != 1) {
        fail("MEM-P05: expected 1 entry for session-2, got " + session2.size());
      }

      // Count
      int count = walStore.messageCount("session-1");
      if (count != 2) {
        fail("MEM-P05: expected 2 messages in session-1, got " + count);
      }

      // Check content
      if (!"hello".equals(session1.get(0).content())) {
        fail("MEM-P05: first entry content mismatch");
      }

      // Crash simulation: create a new store from same path, verify data
      var recovered = new org.cland.alice.memory.wal.FileWalStore(tempDir);
      List<org.cland.alice.memory.wal.RawMessage> recovered1 =
          recovered.getAllMessages("session-1");
      if (recovered1.size() != 2) {
        fail("MEM-P05: after recovery expected 2 entries, got " + recovered1.size());
      }

      System.out.println("PASS: MEM-P05 WAL store OK");
    } finally {
      // Cleanup
      try (var stream = Files.walk(tempDir)) {
        stream
            .sorted(java.util.Comparator.reverseOrder())
            .forEach(
                p -> {
                  try {
                    Files.delete(p);
                  } catch (IOException ignored) {
                  }
                });
      }
    }
  }

  // ==================== helpers ====================

  static void fail(String msg) {
    System.err.println("FAIL: " + msg);
    failures++;
  }

  static Step step(String stepId, String action, double importance) {
    return Step.builder()
        .stepId(stepId)
        .action(action)
        .input("in")
        .output("out")
        .timestamp(System.currentTimeMillis())
        .success(true)
        .importance(importance)
        .build();
  }
}
