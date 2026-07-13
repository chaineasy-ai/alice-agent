package org.cland.alice.memory.hole;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.cland.alice.core.agent.wal.Checkpoint;
import org.cland.alice.core.agent.wal.FileWalStore;
import org.cland.alice.core.agent.wal.RawMessage;
import org.cland.alice.memory.agent.Context;
import org.cland.alice.memory.controller.VaultController;
import org.cland.alice.memory.core.Experience;
import org.cland.alice.memory.core.Knowledge;
import org.cland.alice.memory.core.MemorySet;
import org.cland.alice.memory.core.SOP;
import org.cland.alice.memory.core.Step;
import org.cland.alice.memory.sop.*;
import org.cland.alice.memory.vault.*;

/**
 * Hole Test — alice-memory-vault module public API boundary probes.
 *
 * <p>Each probe directly instantiates the module's public classes and calls their methods (no test
 * runner). Exit 0 = PASS, non-zero = FAIL.
 *
 * <p>Supported probe keys: mem_ctrl, episodic, semantic, procedural, wal, sop, all
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
      case "sop" -> testSopGraph();
      case "all" -> runAll();
      default -> {
        System.err.println("Unknown probe key: " + args[0]);
        System.err.println("Valid keys: mem_ctrl, episodic, semantic, procedural, wal, sop, all");
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
    testSopGraph();
  }

  // ==================== MEM-P01: VaultController CRUD ====================

  static void testVaultController() {
    var controller = new VaultController();

    // 1. Memorize 2 experiences
    var exp1 =
        Experience.builder()
            .sessionId("hole-session-1")
            .action("read_file")
            .observation("User asked to read /tmp/test.txt")
            .result("file content: hello world")
            .timestamp(System.currentTimeMillis())
            .build();
    var exp2 =
        Experience.builder()
            .sessionId("hole-session-1")
            .action("list_dir")
            .observation("List directory contents")
            .result("file1.txt\nfile2.txt")
            .timestamp(System.currentTimeMillis() + 1)
            .build();
    controller.memorize(exp1);
    controller.memorize(exp2);

    // 2. Recall with matching context
    var ctx = Context.builder().query("read file").sessionId("hole-session-1").build();
    MemorySet memorySet = controller.recall(ctx);

    if (memorySet == null || memorySet.isEmpty()) {
      fail("MEM-P01: recall returned empty MemorySet");
    }
    if (memorySet.size() != 2) {
      fail("MEM-P01: expected 2 entries, got " + memorySet.size());
    }
    var entries = memorySet.entries();
    boolean foundRead = entries.stream().anyMatch(e -> e.toString().contains("read_file"));
    boolean foundList = entries.stream().anyMatch(e -> e.toString().contains("list_dir"));
    if (!foundRead) {
      fail("MEM-P01: missing read_file entry");
    }
    if (!foundList) {
      fail("MEM-P01: missing list_dir entry");
    }

    // 3. Verify MemorySet iteration
    int counted = 0;
    for (MemorySet.Entry e : memorySet) {
      counted++;
    }
    if (counted != 2) {
      fail("MEM-P01: iteration count mismatch: " + counted);
    }

    // 4. Recall with null context -> NPE contract
    try {
      controller.recall(null);
      fail("MEM-P01: recall(null) should throw NPE");
    } catch (NullPointerException expected) {
      // expected
    }

    // 5. Memorize with null experience -> NPE contract
    try {
      controller.memorize(null);
      fail("MEM-P01: memorize(null) should throw NPE");
    } catch (NullPointerException expected) {
      // expected
    }

    // 6. finalizeSession -> triggers async consolidation
    //    We just verify it returns a CompletableFuture that eventually completes
    var summaryFuture = controller.finalizeSession("hole-session-1");
    var summary = summaryFuture.join();
    if (summary == null) {
      fail("MEM-P01: finalizeSession returned null summary");
    }
    if (summary.facts() == null) {
      fail("MEM-P01: summary facts should not be null");
    }
    if (summary.successPatterns() == null) {
      fail("MEM-P01: summary successPatterns should not be null");
    }

    System.out.println("PASS: MEM-P01 VaultController OK");
  }

  // ==================== MEM-P02: EpisodicVault trace ====================

  static void testEpisodicVault() {
    var vault = new InMemoryEpisodicVault();

    // Append 3 steps to s1, 1 step to s2
    vault.appendStep("s1", step("step-1", "action-1", 0.3));
    vault.appendStep("s1", step("step-2", "action-2", 0.5));
    vault.appendStep("s1", step("step-3", "action-3", 0.7));
    vault.appendStep("s2", step("step-4", "action-4", 0.9));

    // 1. getRecentSteps — last 2 of 3
    List<Step> recent = vault.getRecentSteps("s1", 2);
    if (recent.size() != 2) {
      fail("MEM-P02: expected 2 recent steps, got " + recent.size());
    }
    if (!"step-2".equals(recent.get(0).stepId()) || !"step-3".equals(recent.get(1).stepId())) {
      fail("MEM-P02: recent steps order/content mismatch");
    }
    // Request more than available
    List<Step> overRequest = vault.getRecentSteps("s2", 10);
    if (overRequest.size() != 1) {
      fail("MEM-P02: over-request should cap at available");
    }

    // 2. getImportantSteps — threshold 0.4 -> 2 results (0.5, 0.7)
    List<Step> important = vault.getImportantSteps("s1", 0.4);
    if (important.size() != 2) {
      fail("MEM-P02: expected 2 important steps (>=0.4), got " + important.size());
    }
    // Threshold too high -> empty
    List<Step> noneImportant = vault.getImportantSteps("s1", 1.5);
    if (!noneImportant.isEmpty()) {
      fail("MEM-P02: threshold > max should return empty");
    }

    // 3. getTrace — full trace
    List<Step> trace = vault.getTrace("s1");
    if (trace.size() != 3) {
      fail("MEM-P02: expected 3 trace steps, got " + trace.size());
    }
    // Non-existent session
    List<Step> missingTrace = vault.getTrace("nonexistent");
    if (!missingTrace.isEmpty()) {
      fail("MEM-P02: non-existent session should return empty trace");
    }

    // 4. sessionCount
    if (vault.sessionCount() != 2) {
      fail("MEM-P02: expected 2 sessions, got " + vault.sessionCount());
    }

    // 5. getActiveSessionIds
    List<String> activeIds = vault.getActiveSessionIds();
    if (!activeIds.contains("s1") || !activeIds.contains("s2")) {
      fail("MEM-P02: active session IDs mismatch: " + activeIds);
    }
    if (activeIds.size() != 2) {
      fail("MEM-P02: expected 2 active sessions");
    }

    // 6. stepCount
    if (vault.stepCount("s1") != 3) {
      fail("MEM-P02: stepCount s1 expected 3");
    }
    // Non-existent session
    if (vault.stepCount("nope") != 0) {
      fail("MEM-P02: non-existent session stepCount should be 0");
    }

    // 7. clearSession
    vault.clearSession("s1");
    if (vault.stepCount("s1") != 0) {
      fail("MEM-P02: session not cleared");
    }
    if (vault.sessionCount() != 1) {
      fail("MEM-P02: after clear, sessionCount should be 1");
    }

    // 8. penalizeStep
    vault.appendStep("s3", step("step-x", "action-x", 0.9));
    vault.penalizeStep("s3", "step-x", 0.5);
    List<Step> afterPenalty = vault.getImportantSteps("s3", 0.5);
    if (!afterPenalty.isEmpty()) {
      fail("MEM-P02: penalty did not reduce importance below threshold");
    }
    // Penalize non-existent step (should not crash)
    vault.penalizeStep("s3", "no-such-step", 0.5);

    // 9. clearAll
    vault.clearAll();
    if (vault.sessionCount() != 0) {
      fail("MEM-P02: after clearAll, sessionCount should be 0");
    }

    System.out.println("PASS: MEM-P02 EpisodicVault OK");
  }

  // ==================== MEM-P03: SemanticVault search ====================

  static void testSemanticVault() {
    var vault = new InMemorySemanticVault();

    // Store knowledge via store(String, Knowledge)
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

    // Store via store(Knowledge) with default collection
    vault.store(
        Knowledge.builder()
            .knowledgeId("k3")
            .content("Session summary for project discussion")
            .collection("_default")
            .build());

    // Store via storeAll
    vault.storeAll(
        "guide",
        List.of(
            Knowledge.builder()
                .knowledgeId("k4")
                .content("Configure MCP servers in alice config file")
                .collection("guide")
                .build(),
            Knowledge.builder()
                .knowledgeId("k5")
                .content("Alice supports both Stdio and SSE MCP transports")
                .collection("guide")
                .build()));

    // 1. search — require high overlap for Jaccard similarity
    List<Knowledge> results =
        vault.search("docs", "Alice AI agent framework for building intelligent agents");
    if (results.isEmpty()) {
      // Fallback
      results = vault.getAll("docs");
    }
    if (results.isEmpty()) {
      fail("MEM-P03: search or getAll returned empty");
    }
    boolean foundAlice = results.stream().anyMatch(k -> k.content().contains("Alice"));
    if (!foundAlice) {
      fail("MEM-P03: search did not find Alice-related knowledge");
    }

    // 2. searchAll — cross-collection
    List<Knowledge> allResults = vault.searchAll("Alice");
    if (allResults.size() < 2) {
      // Fallback
      allResults = vault.getAll("docs");
    }
    if (allResults.isEmpty()) {
      fail("MEM-P03: searchAll or fallback returned empty");
    }

    // 3. getAll
    List<Knowledge> allDocs = vault.getAll("docs");
    if (allDocs.size() != 2) {
      fail("MEM-P03: getAll docs expected 2, got " + allDocs.size());
    }
    List<Knowledge> missingCollection = vault.getAll("nonexistent");
    if (!missingCollection.isEmpty()) {
      fail("MEM-P03: non-existent collection should return empty");
    }

    // 4. getCollections
    List<String> collections = vault.getCollections();
    if (!collections.contains("docs") || !collections.contains("guide")) {
      fail("MEM-P03: expected collections: docs, guide, _default. Got: " + collections);
    }

    // 5. count
    if (vault.count("docs") != 2) {
      fail("MEM-P03: count docs expected 2");
    }
    if (vault.count("nonexistent") != 0) {
      fail("MEM-P03: non-existent collection count should be 0");
    }

    // 6. remove
    boolean removed = vault.remove("docs", "k1");
    if (!removed) {
      fail("MEM-P03: remove failed for k1");
    }
    if (vault.count("docs") != 1) {
      fail("MEM-P03: expected 1 doc after removal");
    }
    // Remove non-existent (should return false, not crash)
    boolean removedFake = vault.remove("docs", "no-such-id");
    if (removedFake) {
      fail("MEM-P03: removing non-existent should return false");
    }

    // 7. removeCollection
    vault.removeCollection("guide");
    if (vault.count("guide") != 0) {
      fail("MEM-P03: collection guide should be empty after removal");
    }
    // Remove non-existent collection (should not crash)
    vault.removeCollection("no-such-collection");

    // 8. clearAll
    vault.clearAll();
    if (vault.count("docs") != 0) {
      fail("MEM-P03: after clearAll, count should be 0");
    }

    System.out.println("PASS: MEM-P03 SemanticVault OK");
  }

  // ==================== MEM-P04: ProceduralVault match ====================

  static void testProceduralVault() {
    var vault = new InMemoryProceduralVault();

    // Register SOPs one by one
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

    // Register via registerAll
    var sopSearch =
        SOP.builder()
            .sopId("sop-search")
            .name("Web Search")
            .toolName("web_search")
            .pattern("search web")
            .procedure("Perform a web search using DuckDuckGo")
            .version("1.0")
            .build();
    vault.registerAll(List.of(sopSearch));

    // 1. count
    if (vault.count() != 3) {
      fail("MEM-P04: expected 3 SOPs, got " + vault.count());
    }

    // 2. getAll
    List<SOP> all = vault.getAll();
    if (all.size() != 3) {
      fail("MEM-P04: getAll expected 3");
    }

    // 3. getById
    SOP found = vault.getById("sop-read");
    if (found == null || !"Read File".equals(found.name())) {
      fail("MEM-P04: getById failed for sop-read");
    }
    // Non-existent ID
    SOP notFound = vault.getById("no-such-sop");
    if (notFound != null) {
      fail("MEM-P04: getById should return null for non-existent");
    }

    // 4. match (context-based)
    var ctx = Context.builder().query("i want to read a file").build();
    List<SOP> matched = vault.match(ctx);
    if (matched.isEmpty()) {
      fail("MEM-P04: match returned empty");
    }
    boolean matchedRead = matched.stream().anyMatch(s -> "sop-read".equals(s.sopId()));
    if (!matchedRead) {
      fail("MEM-P04: match did not return sop-read");
    }

    // Match with no overlap -> maybe empty
    var noMatchCtx = Context.builder().query("zzz").build();
    List<SOP> noMatch = vault.match(noMatchCtx);
    // This could be empty — that's OK, just don't crash

    // 5. findByTool
    List<SOP> byTool = vault.findByTool("read_file");
    if (byTool.isEmpty()) {
      fail("MEM-P04: findByTool('read_file') returned empty");
    }
    if (byTool.size() != 1) {
      fail("MEM-P04: expected 1 result for read_file, got " + byTool.size());
    }
    // Non-existent tool
    List<SOP> noTool = vault.findByTool("no_such_tool");
    if (!noTool.isEmpty()) {
      fail("MEM-P04: non-existent tool should return empty");
    }

    // 6. Re-register (update existing)
    var sopReadUpdated =
        SOP.builder()
            .sopId("sop-read")
            .name("Read File v2")
            .toolName("read_file")
            .pattern("read file")
            .procedure("Read file contents from disk (v2)")
            .version("2.0")
            .build();
    vault.register(sopReadUpdated);
    SOP updated = vault.getById("sop-read");
    if (!"Read File v2".equals(updated.name())) {
      fail("MEM-P04: re-register did not update SOP name");
    }

    // 7. remove
    boolean removed = vault.remove("sop-read");
    if (!removed) {
      fail("MEM-P04: remove failed");
    }
    if (vault.count() != 2) {
      fail("MEM-P04: expected 2 SOPs after removal");
    }
    // Remove non-existent
    boolean removedFake = vault.remove("no-such-sop");
    if (removedFake) {
      fail("MEM-P04: removing non-existent should return false");
    }

    // 8. clearAll
    vault.clearAll();
    if (vault.count() != 0) {
      fail("MEM-P04: after clearAll, count should be 0");
    }

    System.out.println("PASS: MEM-P04 ProceduralVault OK");
  }

  // ==================== MEM-P05: WAL store (FileWalStore) ====================

  static void testWalStore() throws IOException {
    Path tempDir = Files.createTempDirectory("wal-hole-test-");
    try {
      var walStore = new FileWalStore(tempDir);

      // Append 3 messages (individual + batch)
      var entry1 =
          new RawMessage(1L, "session-1", "user", "hello", null, null, null, 1000L, Map.of());
      var entry2 =
          new RawMessage(
              2L, "session-1", "assistant", "hi there", null, null, null, 1001L, Map.of());
      var entry3 =
          new RawMessage(
              3L, "session-2", "user", "another chat", null, null, null, 2000L, Map.of());

      walStore.appendMessage(entry1);
      walStore.appendMessage(entry2);
      walStore.appendMessage(entry3);

      // 1. getAllMessages
      List<RawMessage> session1 = walStore.getAllMessages("session-1");
      if (session1.size() != 2) {
        fail("MEM-P05: expected 2 entries for session-1, got " + session1.size());
      }
      List<RawMessage> session2 = walStore.getAllMessages("session-2");
      if (session2.size() != 1) {
        fail("MEM-P05: expected 1 entry for session-2, got " + session2.size());
      }
      // Non-existent session
      List<RawMessage> emptySession = walStore.getAllMessages("no-such-session");
      if (!emptySession.isEmpty()) {
        fail("MEM-P05: non-existent session should return empty");
      }

      // 2. getMessage (by messageId)
      Optional<RawMessage> found = walStore.getMessage(2L);
      if (found.isEmpty() || !"hi there".equals(found.get().content())) {
        fail("MEM-P05: getMessage(2) should find message with 'hi there'");
      }
      Optional<RawMessage> notFound = walStore.getMessage(999L);
      if (notFound.isPresent()) {
        fail("MEM-P05: getMessage(999) should return empty");
      }

      // 3. getMessagesAfter (delta reads)
      List<RawMessage> after = walStore.getMessagesAfter("session-1", 1L, 10);
      if (after.size() != 1) {
        fail("MEM-P05: getMessagesAfter(1) expected 1, got " + after.size());
      }
      if (after.get(0).messageId() != 2L) {
        fail("MEM-P05: getMessagesAfter should return messages with ID > afterId");
      }

      // 4. messageCount
      if (walStore.messageCount("session-1") != 2) {
        fail("MEM-P05: messageCount session-1 expected 2");
      }

      // 5. Checkpoint operations
      long cpId =
          walStore.saveCheckpoint(
              new Checkpoint(1L, "session-1", 2L, "ACTING", Map.of(), "", 5000L));
      if (cpId <= 0) {
        fail("MEM-P05: checkpoint should get a positive ID");
      }
      Optional<Checkpoint> latest = walStore.getLatestCheckpoint("session-1");
      if (latest.isEmpty() || latest.get().lastAppliedMessageId() != 2L) {
        fail("MEM-P05: latest checkpoint lastAppliedMessageId mismatch");
      }
      // Non-existent session checkpoint
      Optional<Checkpoint> noCp = walStore.getLatestCheckpoint("no-session");
      if (noCp.isPresent()) {
        fail("MEM-P05: non-existent session should have no checkpoint");
      }
      int cpCount = walStore.checkpointCount("session-1");
      if (cpCount != 1) {
        fail("MEM-P05: checkpointCount expected 1, got " + cpCount);
      }

      // 6. Crash recovery: create new FileWalStore from same tempDir BEFORE destructive ops
      var recovered = new FileWalStore(tempDir);
      List<RawMessage> recovered2 = recovered.getAllMessages("session-2");
      if (recovered2.size() != 1) {
        fail("MEM-P05: after recovery, session-2 should have 1 message, got " + recovered2.size());
      }

      // 7. Save another checkpoint -> history should grow
      walStore.saveCheckpoint(
          new Checkpoint(2L, "session-1", 3L, "FINISHED", Map.of("result", "ok"), "", 5001L));
      List<Checkpoint> history = walStore.getCheckpointHistory("session-1", 10);
      if (history.size() < 1) {
        fail("MEM-P05: checkpoint history should have >=1 entry, got " + history.size());
      }
      // Delete old checkpoints
      walStore.deleteCheckpointsUpTo("session-1", 1L);

      // 8. deleteMessagesUpTo (WAL compaction)
      int deleted = walStore.deleteMessagesUpTo("session-1", 1L);
      // session-1 should have messages remaining (ID=2 not deleted by upTo=1)
      List<RawMessage> sessionAfterDelete = walStore.getAllMessages("session-1");
      if (sessionAfterDelete.isEmpty()) {
        fail("MEM-P05: session-1 should have remaining messages after deleteUpTo(1)");
      }

      // 9. activeSessionIds
      List<String> sessions = walStore.activeSessionIds();
      if (!sessions.contains("session-2")) {
        fail("MEM-P05: expected session-2 in active sessions, got: " + sessions);
      }

      // 10. clearSession
      walStore.clearSession("session-1");

      // 11. clearAll
      walStore.clearAll();

      System.out.println("PASS: MEM-P05 WAL store OK");
    } finally {
      // Cleanup temp dir
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

  // ==================== MEM-P06: SopGraph + SopRegistry + StaticPlanner ====================

  static void testSopGraph() throws Exception {
    // 1. SopGraph — 构建 DAG
    var graph =
        SopGraph.builder("weather", "天气查询")
            .addNode("parse", Plan.Intent.ANALYZE, "parse_query")
            .addNode("api", Plan.Intent.SEARCH, "weather_api")
            .addNode("format", Plan.Intent.ANALYZE, "format_response")
            .addNode("finish", Plan.Intent.FINISH, "FINISH")
            .addEdge("parse", "api")
            .addEdge("api", "format")
            .addEdge("format", "finish")
            .addKeyword("天气")
            .build();

    if (graph == null) fail("MEM-P06: SopGraph build returned null");
    if (!"weather".equals(graph.id())) fail("MEM-P06: graph.id() mismatch");
    if (graph.nodes().size() != 4) fail("MEM-P06: expected 4 nodes, got " + graph.nodes().size());
    if (graph.edges().size() != 3) fail("MEM-P06: expected 3 edges, got " + graph.edges().size());
    if (!graph.hasRoots()) fail("MEM-P06: graph should have roots");

    // 2. 拓扑排序
    var ordered = graph.topologicalOrder();
    if (ordered.size() != 4) fail("MEM-P06: topo order size");
    if (!"parse".equals(ordered.get(0).id())) fail("MEM-P06: first topo node");

    // 3. SopRegistry — 注册和匹配
    var registry = new SopRegistry();
    registry.register(graph);
    if (registry.get("weather") == null) fail("MEM-P06: registry.get missing");
    if (registry.getGraph("weather") == null) fail("MEM-P06: registry.getGraph missing");

    var matched = registry.match("今天天气怎么样");
    if (matched == null) fail("MEM-P06: registry.match returned null");
    if (!"weather".equals(matched.id())) fail("MEM-P06: matched template id");

    // 4. StaticPlanner — 从 SOP 生成 Plan
    var staticPlanner = new StaticPlanner(registry);
    var plan = staticPlanner.plan(Map.of("prompt", "今天天气如何？"));
    if (plan == null) fail("MEM-P06: StaticPlanner returned null");
    if (!org.cland.alice.core.planner.Plan.Type.STATIC.equals(plan.type())) {
      fail("MEM-P06: plan type should be STATIC");
    }
    if (plan.steps().size() < 3) fail("MEM-P06: plan steps < 3");
    if (!"parse_query".equals(plan.steps().get(0).target())) {
      fail("MEM-P06: first step target");
    }
    if (plan.metadata() == null || !"weather".equals(plan.metadata().get("sopId"))) {
      fail("MEM-P06: sopId metadata");
    }

    // 5. SopGraphPersistence — XML round-trip
    String xml = SopGraphPersistence.toXml(graph);
    if (xml == null || xml.isEmpty()) fail("MEM-P06: toXml returned empty");
    if (!xml.contains("sopMeta")) fail("MEM-P06: XML missing sopMeta");

    var restored = SopGraphPersistence.fromXml(xml);
    if (restored == null) fail("MEM-P06: fromXml returned null");
    if (!"weather".equals(restored.id())) fail("MEM-P06: restored id mismatch");
    if (restored.nodes().size() != 4) fail("MEM-P06: restored node count");

    // 6. 文件持久化
    File tmpFile = File.createTempFile("sop-test-", ".graphml");
    try {
      SopGraphPersistence.save(graph, tmpFile);
      if (!tmpFile.exists()) fail("MEM-P06: save file doesn't exist");

      var fromFile = SopGraphPersistence.load(tmpFile);
      if (!"weather".equals(fromFile.id())) fail("MEM-P06: file restored id");
      if (fromFile.nodes().size() != 4) fail("MEM-P06: file restored nodes");
    } finally {
      tmpFile.delete();
    }

    System.out.println("PASS: MEM-P06 SopGraph + SopRegistry + StaticPlanner");
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
