package org.cland.alice.env.adapter;

import org.cland.alice.env.adapter.model.Resource;
import org.cland.alice.env.adapter.model.ResourceResult;
import org.cland.alice.env.adapter.model.Tool;
import org.cland.alice.env.adapter.model.ToolResult;
import org.cland.alice.env.adapter.transport.McpTransport;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * MCP 2.0 protocol client — represents a single connection to an external
 * MCP-compatible server (e.g., Filesystem, Database, GitHub).
 * <p>
 * Lifecycle:
 * <ol>
 *   <li>{@link #connect()} — perform MCP handshake, discover capabilities</li>
 *   <li>{@link #listTools()} / {@link #listResources()} — discover capabilities</li>
 *   <li>{@link #callTool(String, Map)} — invoke a tool</li>
 *   <li>{@link #readResource(String)} — read a resource</li>
 *   <li>{@link #disconnect()} — tear down connection</li>
 * </ol>
 */
public final class McpClient {

    private static final System.Logger logger =
        System.getLogger(McpClient.class.getName());

    private final String serverId;
    private final McpTransport transport;

    // ========== Capabilities (populated after handshake) ==========

    private final List<Tool> tools = new CopyOnWriteArrayList<>();
    private final List<Resource> resources = new CopyOnWriteArrayList<>();
    private final AtomicReference<ServerCapabilities> serverCapabilities =
        new AtomicReference<>();

    // ========== State ==========

    private volatile ClientState state = ClientState.DISCONNECTED;
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();

    enum ClientState {
        DISCONNECTED,
        CONNECTING,
        INITIALIZING,
        READY,
        DISCONNECTING,
        ERROR
    }

    /**
     * Create a new MCP client.
     *
     * @param serverId  unique identifier for this server connection
     * @param transport the transport layer (Stdio or SSE)
     */
    public McpClient(String serverId, McpTransport transport) {
        this.serverId = Objects.requireNonNull(serverId, "serverId must not be null");
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
    }

    // ========== Lifecycle ==========

    /**
     * Connect to the MCP server and perform the initialization handshake.
     * <p>
     * The handshake includes:
     * <ol>
     *   <li>Transport-level connection</li>
     *   <li>MCP {@code initialize} request (Protocol 2.0)</li>
     *   <li>Capability discovery (tools/list, resources/list)</li>
     * </ol>
     *
     * @return a future that completes when initialization is finished
     */
    public CompletableFuture<Void> connect() {
        if (state != ClientState.DISCONNECTED) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("Client is in state: " + state));
        }
        state = ClientState.CONNECTING;

        return transport.connect()
            .thenCompose(v -> performHandshake())
            .thenRun(() -> {
                state = ClientState.READY;
                logger.log(System.Logger.Level.INFO,
                    "MCP client '{0}' ready with {1} tools, {2} resources",
                    serverId, tools.size(), resources.size());
            })
            .exceptionally(e -> {
                state = ClientState.ERROR;
                logger.log(System.Logger.Level.ERROR,
                    "MCP client '{0}' connection failed: {1}",
                    serverId, e.getMessage());
                throw new RuntimeException(e);
            });
    }

    /**
     * Call a tool on the MCP server.
     *
     * @param toolName the tool name (e.g., "filesystem/read")
     * @param params   tool parameters
     * @return the tool result
     */
    public CompletableFuture<ToolResult> callTool(String toolName, Map<String, Object> params) {
        if (state != ClientState.READY) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("Client not ready, state: " + state));
        }

        String request = buildJsonRpcRequest("tools/call", Map.of(
            "name", toolName,
            "arguments", params != null ? params : Map.of()
        ));

        return transport.send(request)
            .thenApply(this::parseToolResult);
    }

    /**
     * Read a resource from the MCP server.
     *
     * @param uri the resource URI
     * @return the resource content
     */
    public CompletableFuture<ResourceResult> readResource(String uri) {
        if (state != ClientState.READY) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("Client not ready, state: " + state));
        }

        String request = buildJsonRpcRequest("resources/read", Map.of("uri", uri));

        return transport.send(request)
            .thenApply(this::parseResourceResult);
    }

    /**
     * Subscribe to resource change notifications.
     *
     * @param uri the resource URI to subscribe to
     * @return a future that completes when the subscription is confirmed
     */
    public CompletableFuture<Void> subscribeResource(String uri) {
        String request = buildJsonRpcRequest("resources/subscribe", Map.of("uri", uri));
        return transport.send(request).thenApply(r -> null);
    }

    /**
     * List all available tools on the server.
     *
     * @return unmodifiable list of tools
     */
    public List<Tool> listTools() {
        return Collections.unmodifiableList(tools);
    }

    /**
     * List all available resources on the server.
     *
     * @return unmodifiable list of resources
     */
    public List<Resource> listResources() {
        return Collections.unmodifiableList(resources);
    }

    /**
     * Get the server capabilities discovered during handshake.
     */
    public ServerCapabilities serverCapabilities() {
        return serverCapabilities.get();
    }

    /**
     * Disconnect from the server and release resources.
     */
    public void disconnect() {
        state = ClientState.DISCONNECTING;
        transport.disconnect();
        tools.clear();
        resources.clear();
        serverCapabilities.set(null);
        state = ClientState.DISCONNECTED;
        logger.log(System.Logger.Level.INFO,
            "MCP client '{0}' disconnected", serverId);
    }

    /**
     * Check if the client is in READY state and can accept requests.
     */
    public boolean isReady() {
        return state == ClientState.READY;
    }

    /**
     * Get the current client state.
     */
    public ClientState state() {
        return state;
    }

    /**
     * Get a reference to the underlying transport.
     */
    public McpTransport transport() {
        return transport;
    }

    // ========== Getters ==========

    public String serverId() {
        return serverId;
    }

    public Map<String, Object> attributes() {
        return Collections.unmodifiableMap(attributes);
    }

    public McpClient attribute(String key, Object value) {
        attributes.put(key, value);
        return this;
    }

    // ========== Handshake ==========

    private CompletableFuture<Void> performHandshake() {
        state = ClientState.INITIALIZING;

        // Step 1: Initialize
        String initRequest = buildJsonRpcRequest("initialize", Map.of(
            "protocolVersion", "2.0",
            "clientInfo", Map.of(
                "name", "alice-agent",
                "version", "0.1.0"
            ),
            "capabilities", Map.of(
                "tools", Map.of(),
                "resources", Map.of(
                    "subscribe", true
                ),
                "prompts", Map.of()
            )
        ));

        return transport.send(initRequest)
            .thenCompose(this::handleInitializeResponse)
            .thenCompose(v -> discoverCapabilities());
    }

    @SuppressWarnings("unchecked")
    private CompletableFuture<Void> handleInitializeResponse(String responseJson) {
        try {
            var gson = new com.google.gson.Gson();
            var map = gson.fromJson(responseJson, java.util.Map.class);

            var result = (Map<String, Object>) map.get("result");
            if (result == null) {
                return CompletableFuture.failedFuture(
                    new RuntimeException("Initialize failed: " + responseJson));
            }

            // Parse server capabilities
            var caps = (Map<String, Object>) result.get("capabilities");
            if (caps != null) {
                serverCapabilities.set(new ServerCapabilities(
                    caps.containsKey("tools"),
                    caps.containsKey("resources"),
                    caps.containsKey("prompts"),
                    caps.containsKey("experimental")
                ));
            }

            // Send initialized notification
            String initializedNotif = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}";
            return transport.send(initializedNotif).thenApply(r -> null);

        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    @SuppressWarnings("unchecked")
    private CompletableFuture<Void> discoverCapabilities() {
        var serverCaps = serverCapabilities.get();
        if (serverCaps == null) {
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<Void> result = CompletableFuture.completedFuture(null);

        // Discover tools
        if (serverCaps.supportsTools()) {
            result = result.thenCompose(v -> {
                String toolsRequest = buildJsonRpcRequest("tools/list", Map.of());
                return transport.send(toolsRequest);
            }).thenAccept(responseJson -> {
                try {
                    var gson = new com.google.gson.Gson();
                    var map = gson.fromJson(responseJson, java.util.Map.class);
                    var toolsResult = (Map<String, Object>) map.get("result");
                    if (toolsResult != null) {
                        var toolList = (List<Map<String, Object>>) toolsResult.get("tools");
                        if (toolList != null) {
                            for (var t : toolList) {
                                tools.add(Tool.builder()
                                    .name((String) t.get("name"))
                                    .description((String) t.get("description"))
                                    .inputSchema((Map<String, Object>) t.get("inputSchema"))
                                    .build());
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.log(System.Logger.Level.WARNING,
                        "Failed to parse tools list: {0}", e.getMessage());
                }
            });
        }

        // Discover resources
        if (serverCaps.supportsResources()) {
            result = result.thenCompose(v -> {
                String resourcesRequest = buildJsonRpcRequest("resources/list", Map.of());
                return transport.send(resourcesRequest);
            }).thenAccept(responseJson -> {
                try {
                    var gson = new com.google.gson.Gson();
                    var map = gson.fromJson(responseJson, java.util.Map.class);
                    var resourcesResult = (Map<String, Object>) map.get("result");
                    if (resourcesResult != null) {
                        var resourceList = (List<Map<String, Object>>) resourcesResult.get("resources");
                        if (resourceList != null) {
                            for (var r : resourceList) {
                                resources.add(Resource.builder()
                                    .uri((String) r.get("uri"))
                                    .mimeType((String) r.get("mimeType"))
                                    .name((String) r.get("name"))
                                    .description((String) r.get("description"))
                                    .build());
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.log(System.Logger.Level.WARNING,
                        "Failed to parse resources list: {0}", e.getMessage());
                }
            });
        }

        return result;
    }

    // ========== JSON-RPC Utilities ==========

    private String buildJsonRpcRequest(String method, Map<String, Object> params) {
        var gson = new com.google.gson.Gson();
        var map = new java.util.LinkedHashMap<String, Object>();
        map.put("jsonrpc", "2.0");
        map.put("method", method);
        map.put("params", params);
        return gson.toJson(map);
    }

    @SuppressWarnings("unchecked")
    private ToolResult parseToolResult(String responseJson) {
        try {
            var gson = new com.google.gson.Gson();
            var map = gson.fromJson(responseJson, java.util.Map.class);

            // Check for error
            if (map.containsKey("error")) {
                var error = (Map<String, Object>) map.get("error");
                String message = error != null
                    ? (String) error.get("message")
                    : "Unknown error";
                return ToolResult.error(message);
            }

            var result = (Map<String, Object>) map.get("result");
            if (result == null) {
                return ToolResult.error("No result in response");
            }

            var contentList = (List<Map<String, Object>>) result.get("content");
            boolean isError = Boolean.TRUE.equals(result.get("isError"));

            StringBuilder text = new StringBuilder();
            Map<String, Object> dataMap = new java.util.LinkedHashMap<>();

            if (contentList != null) {
                for (var item : contentList) {
                    String type = (String) item.get("type");
                    if ("text".equals(type)) {
                        if (!text.isEmpty()) text.append('\n');
                        text.append(item.getOrDefault("text", ""));
                    } else if ("data".equals(type)) {
                        @SuppressWarnings("unchecked")
                        var jsonData = (Map<String, Object>) item.get("json");
                        if (jsonData != null) {
                            dataMap.putAll(jsonData);
                        }
                    }
                }
            }

            return ToolResult.builder()
                .status(isError ? ToolResult.Status.ERROR : ToolResult.Status.SUCCESS)
                .text(text.toString())
                .content(dataMap)
                .isError(isError)
                .build();

        } catch (Exception e) {
            return ToolResult.error("Parse error: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private ResourceResult parseResourceResult(String responseJson) {
        try {
            var gson = new com.google.gson.Gson();
            var map = gson.fromJson(responseJson, java.util.Map.class);

            if (map.containsKey("error")) {
                var error = (Map<String, Object>) map.get("error");
                String message = error != null
                    ? (String) error.get("message")
                    : "Unknown error";
                throw new RuntimeException("Resource read error: " + message);
            }

            var result = (Map<String, Object>) map.get("result");
            if (result == null) {
                throw new RuntimeException("No result in response");
            }

            String uri = (String) result.get("uri");
            String mimeType = (String) result.get("mimeType");
            String text = (String) result.get("text");

            // Parse structured data if present
            Map<String, Object> data = Collections.emptyMap();
            Object dataObj = result.get("data");
            if (dataObj instanceof Map) {
                @SuppressWarnings("unchecked")
                var m = (Map<String, Object>) dataObj;
                data = m;
            }

            long size = text != null ? text.length() : 0;

            return ResourceResult.builder()
                .uri(uri)
                .mimeType(mimeType)
                .text(text)
                .data(data)
                .sizeBytes(size)
                .build();

        } catch (Exception e) {
            throw new RuntimeException("Failed to parse resource result", e);
        }
    }

    // ========== Server Capabilities ==========

    /**
     * Describes the capabilities of the MCP server as discovered during handshake.
     */
    public record ServerCapabilities(
        boolean supportsTools,
        boolean supportsResources,
        boolean supportsPrompts,
        boolean supportsExperimental
    ) {}

    @Override
    public String toString() {
        return "McpClient{serverId='" + serverId + "', state=" + state
            + ", tools=" + tools.size() + ", resources=" + resources.size() + "}";
    }
}
