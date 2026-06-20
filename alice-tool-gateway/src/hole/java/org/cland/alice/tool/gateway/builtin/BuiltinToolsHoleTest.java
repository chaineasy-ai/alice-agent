package org.cland.alice.tool.gateway.builtin;

import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.cland.alice.tool.gateway.ToolRegistry;
import org.cland.alice.tool.gateway.engine.ExecutionEngine;
import org.cland.alice.tool.gateway.engine.ToolDiscovery;
import org.cland.alice.tool.gateway.engine.ToolResult;
import org.cland.alice.tool.gateway.metadata.McpToolAdapter;
import org.cland.alice.tool.gateway.model.McpTool;

/**
 * Hole test entry point for alice-tool-gateway.
 *
 * <p>Exercises module boundary (ToolDiscovery → ToolRegistry → ExecutionEngine → SandboxProvider)
 * directly, without going through Gradle unit tests.
 *
 * <p>Usage (via Gradle): ./gradlew :alice-tool-gateway:runHoleTest --args="&lt;toolKey&gt;
 * [args...]"
 *
 * <p>Supported toolKeys: lookup, list, scan, invoke, sandbox, builtins, web_search, mcp_tool,
 * mcp_registry
 *
 * <p>Exit 0 = PASS, non-zero = FAIL.
 */
public class BuiltinToolsHoleTest {

  /** Absolute path to project root, detected from working dir at runtime. */
  static final String PROJ_ROOT;

  /**
   * Gradle JavaExec working directory is the module directory (alice-tool-gateway/), so we go up
   * one level to reach the project root.
   */
  static {
    String cwd = Paths.get("..").toAbsolutePath().normalize().toString();
    PROJ_ROOT = cwd.replace('\\', '/');
  }

  public static void main(String[] args) throws Exception {
    if (args.length == 0) {
      fail(
          "Usage: <toolKey> [args...]\n"
              + "  lookup, list, scan, invoke, sandbox, builtins, web_search");
    }

    switch (args[0]) {
      case "lookup" -> testToolRegistryLookup();
      case "list" -> testToolRegistryList();
      case "scan" -> testToolDiscoveryScan();
      case "invoke" -> testExecutionEngineInvoke();
      case "sandbox" -> testSandboxProvider();
      case "builtins" -> testAllBuiltinTools();
      case "web_search" -> testWebSearch(args);
      case "mcp_tool" -> testMcpToolModel();
      case "mcp_registry" -> testMcpToolInRegistry();
      case "mcp_debug" -> testMcpDebug();
      case "all" -> {
        testToolRegistryLookup();
        testToolRegistryList();
        testToolDiscoveryScan();
        testExecutionEngineInvoke();
        testSandboxProvider();
        testAllBuiltinTools();
        testMcpToolModel();
        testMcpToolInRegistry();
      }
      default -> fail("Unknown toolKey: " + args[0]);
    }
  }

  /** Resolve a path relative to project root. */
  static String p(String relative) {
    return PROJ_ROOT + "/" + relative;
  }

  // ==================== TGW-P01: ToolRegistry.lookup() ====================

  static void testToolRegistryLookup() {
    var registry = new ToolRegistry();
    registerBuiltin(registry);

    var expectedNames =
        Set.of(
            "read_file",
            "write_file",
            "grep",
            "run",
            "list_dir",
            "file_exists",
            "search_file",
            "remove_file",
            "web_search");

    for (String name : expectedNames) {
      var meta = registry.lookup(name);
      if (meta == null) {
        fail("lookup('" + name + "') returned null");
      }
    }
    System.out.println("PASS: lookup OK (" + expectedNames.size() + " tools registered)");
  }

  // ==================== TGW-P05: ToolRegistry.toolNames() / allTools() ====================

  static void testToolRegistryList() {
    var registry = new ToolRegistry();
    registerBuiltin(registry);

    var names = registry.toolNames();
    if (names == null || names.isEmpty()) {
      fail("toolNames() returned empty");
    }
    var all = registry.allTools();
    if (all == null || all.isEmpty()) {
      fail("allTools() returned empty");
    }
    if (names.size() != all.size()) {
      fail("toolNames().size() != allTools().size(): " + names.size() + " vs " + all.size());
    }
    System.out.println("PASS: tool list OK (" + names.size() + " tools)");
  }

  // ==================== TGW-P06: BuiltinTools direct invocation ====================

  static void testAllBuiltinTools() {
    var tools = new BuiltinTools();
    String src =
        p(
            "alice-tool-gateway/src/hole/java/org/cland/alice/tool/gateway/builtin/BuiltinToolsHoleTest.java");
    String holeDir = p("alice-tool-gateway/src/hole/java");
    String tmp = p("alice-tool-gateway/build/hole-test-tmp.txt");

    // read_file
    try {
      String content = tools.readFile(src);
      if (content == null || !content.contains("BuiltinToolsHoleTest")) {
        fail("read_file returned unexpected content");
      }
    } catch (Exception e) {
      fail("read_file threw: " + e);
    }

    // write_file + remove_file round-trip
    try {
      tools.writeFile(tmp, "hole test content");
      String readBack = tools.readFile(tmp);
      if (!"hole test content".equals(readBack)) {
        fail("write_file/read_file round-trip failed");
      }
      tools.removeFile(tmp);
    } catch (Exception e) {
      fail("write_file / remove_file threw: " + e);
    }

    // grep
    try {
      String grepResult = tools.grep("BuiltinToolsHoleTest", src);
      if (grepResult == null || !grepResult.contains("BuiltinToolsHoleTest")) {
        fail("grep returned unexpected result");
      }
    } catch (Exception e) {
      fail("grep threw: " + e);
    }

    // run
    try {
      String shellResult = tools.run("echo hole-test-ok");
      if (shellResult == null || !shellResult.contains("hole-test-ok")) {
        fail("run returned unexpected result: " + shellResult);
      }
    } catch (Exception e) {
      fail("run threw: " + e);
    }

    // list_dir
    try {
      String listing = tools.listDir(holeDir);
      if (listing == null || listing.isEmpty()) {
        fail("list_dir returned empty");
      }
    } catch (Exception e) {
      fail("list_dir threw: " + e);
    }

    // file_exists
    try {
      String existsResult = tools.fileExists(src);
      if (!"true".equals(existsResult)) {
        fail("file_exists returned '" + existsResult + "' for own source");
      }
    } catch (Exception e) {
      fail("file_exists threw: " + e);
    }

    // search_file
    try {
      String found = tools.searchFile(holeDir, "**/BuiltinToolsHoleTest.java", "-1");
      if (found == null || !found.contains("BuiltinToolsHoleTest")) {
        fail("search_file returned unexpected: " + found);
      }
    } catch (Exception e) {
      fail("search_file threw: " + e);
    }

    System.out.println("PASS: all builtin tools OK");
  }

  // ==================== TGW-P03: ExecutionEngine.invoke() ====================

  static void testExecutionEngineInvoke() {
    var registry = new ToolRegistry();
    registerBuiltin(registry);

    var engine = ExecutionEngine.builder().registry(registry).build();
    var params = new java.util.LinkedHashMap<String, Object>();
    params.put("path", p("alice-tool-gateway/src/hole/java"));
    var result = engine.invoke("list_dir", params);
    if (result.status() != ToolResult.Status.SUCCESS) {
      fail("invoke('list_dir') failed: " + result.summary());
    }
    if (result.rawData() == null || result.rawData().isEmpty()) {
      fail("invoke('list_dir') returned empty data");
    }
    System.out.println("PASS: invoke OK (" + result.rawData().length() + " chars)");
  }

  // ==================== TGW-P04: SandboxProvider ====================

  static void testSandboxProvider() {
    var registry = new ToolRegistry();
    registerBuiltin(registry);

    var engine = ExecutionEngine.builder().registry(registry).build();

    var runParams = new java.util.LinkedHashMap<String, Object>();
    runParams.put("command", "echo sandbox-test");
    var result = engine.invoke("run", runParams);
    if (result.status() != ToolResult.Status.SUCCESS) {
      fail("sandbox invoke('run') failed: " + result.summary());
    }
    System.out.println("PASS: sandbox OK");
  }

  // ==================== TGW-P02: ToolDiscovery.scanAndRegister() ====================

  static void testToolDiscoveryScan() {
    var registry = new ToolRegistry();
    var discovery = new ToolDiscovery(registry);
    discovery.scanAndRegister(List.of(new BuiltinTools()));

    var names = registry.toolNames();
    if (names == null || names.isEmpty()) {
      fail("scanAndRegister left registry empty");
    }
    System.out.println("PASS: scanAndRegister OK (" + names.size() + " tools)");
  }

  // ==================== TGW-P07: web_search ====================

  static void testWebSearch(String[] args) {
    if (args.length < 2) {
      fail("web_search requires a query argument");
    }
    String query = args[1];
    String maxResults = args.length >= 3 ? args[2] : "5";
    var tools = new BuiltinTools();
    try {
      String result = tools.webSearch(query, maxResults);
      System.out.println("PASS: web_search OK (" + result.length() + " chars)");
      System.out.println(result.substring(0, Math.min(result.length(), 500)));
    } catch (java.net.http.HttpConnectTimeoutException e) {
      System.out.println("SKIP: network unavailable: " + e.getMessage());
    } catch (Exception e) {
      fail("web_search threw: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  // ==================== TGW-P08: McpTool model ====================

  static void testMcpToolModel() {
    var tool =
        McpTool.builder()
            .serverId("filesystem")
            .toolName("read")
            .description("Read file contents")
            .inputSchema(
                Map.of("type", "object", "properties", Map.of("path", Map.of("type", "string"))))
            .invoker(params -> "file content: " + params.get("path"))
            .build();

    if (!"filesystem:read".equals(tool.qualifiedName())) {
      fail("qualifiedName mismatch: " + tool.qualifiedName());
    }
    if (!"filesystem".equals(tool.serverId())) {
      fail("serverId mismatch");
    }
    if (!"read".equals(tool.toolName())) {
      fail("toolName mismatch");
    }

    var result = tool.invoke(Map.of("path", "/tmp/test.txt"));
    if (!"file content: /tmp/test.txt".equals(result)) {
      fail("invoke returned unexpected: " + result);
    }

    // Error path
    var errTool =
        McpTool.builder()
            .serverId("test")
            .toolName("crash")
            .invoker(
                params -> {
                  throw new RuntimeException("simulated error");
                })
            .build();
    var errResult = errTool.invoke(Map.of());
    if (!errResult.contains("simulated error")) {
      fail("error invoke did not contain expected message: " + errResult);
    }

    System.out.println("PASS: McpTool model OK");
  }

  // ==================== TGW-P09: McpToolAdapter + ToolRegistry integration ====================

  static void testMcpToolInRegistry() {
    var registry = new ToolRegistry();

    // Create MCP tool and convert to metadata
    var mcpTool =
        McpTool.builder()
            .serverId("filesystem")
            .toolName("read")
            .description("Read file contents")
            .inputSchema(Map.of("type", "object"))
            .invoker(params -> "mock file content")
            .build();

    var metadata = McpToolAdapter.toToolMetadata(mcpTool);
    registry.register(metadata);

    // Lookup and verify
    var found = registry.lookup("filesystem:read");
    if (found == null) {
      fail("MCP tool not found in registry");
    }
    if (!"filesystem:read".equals(found.name())) {
      fail("name mismatch: " + found.name());
    }

    // Execute through ExecutionEngine
    var engine = ExecutionEngine.builder().registry(registry).build();
    var result = engine.invoke("filesystem:read", Map.of());
    if (result.status() != ToolResult.Status.SUCCESS) {
      fail("execution failed: " + result.summary());
    }
    if (!"mock file content".equals(result.rawData())) {
      fail("execution returned unexpected data: " + result.rawData());
    }

    // Unregister and verify removal
    registry.unregister("filesystem:read");
    if (registry.hasTool("filesystem:read")) {
      fail("tool not removed after unregister");
    }

    System.out.println("PASS: McpTool in registry OK");
  }

  // ==================== helpers ====================

  static void registerBuiltin(ToolRegistry registry) {
    var discovery = new ToolDiscovery(registry);
    discovery.scanAndRegister(List.of(new BuiltinTools()));
  }

  static void fail(String msg) {
    System.err.println("FAIL: " + msg);
    System.exit(1);
  }

  // ==================== Debug: isolate MCP registry hang ====================

  static void testMcpDebug() {
    var registry = new ToolRegistry();

    var mcpTool =
        McpTool.builder()
            .serverId("filesystem")
            .toolName("read")
            .description("Read file contents")
            .inputSchema(Map.of("type", "object"))
            .invoker(params -> "mock file content")
            .build();

    System.out.println("[debug] tool created");
    System.out.flush();

    var metadata = McpToolAdapter.toToolMetadata(mcpTool);
    registry.register(metadata);
    System.out.println("[debug] registered");
    System.out.flush();

    var found = registry.lookup("filesystem:read");
    System.out.println("[debug] lookup OK: " + found.name());
    System.out.flush();

    var engine = ExecutionEngine.builder().registry(registry).build();
    System.out.println("[debug] engine built");
    System.out.flush();

    System.out.println("[debug] invoke starting... (30s default timeout)");
    System.out.flush();
    long t0 = System.currentTimeMillis();
    var result = engine.invoke("filesystem:read", Map.of());
    long elapsed = System.currentTimeMillis() - t0;
    System.out.println("[debug] invoke returned after " + elapsed + "ms");
    System.out.println(
        "[debug] status="
            + result.status()
            + " data='"
            + result.rawData()
            + "' summary='"
            + result.summary()
            + "'");
    System.out.flush();

    if (result.status() != ToolResult.Status.SUCCESS) {
      fail("invoke failed: " + result.summary());
    }
    if (!"mock file content".equals(result.rawData())) {
      fail("unexpected data: " + result.rawData());
    }
    System.out.println("PASS: McpTool mcp_debug (" + elapsed + "ms)");
  }
}
