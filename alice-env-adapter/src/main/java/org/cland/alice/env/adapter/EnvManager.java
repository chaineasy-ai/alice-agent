package org.cland.alice.env.adapter;

import org.cland.alice.env.adapter.model.ResourceResult;
import org.cland.alice.env.adapter.model.Tool;
import org.cland.alice.env.adapter.model.ToolResult;
import org.cland.alice.env.adapter.snapshot.EnvSnapshot;
import org.cland.alice.env.adapter.snapshot.SnapshotManager;
import org.cland.alice.env.adapter.state.EnvState;
import org.cland.alice.env.adapter.transport.McpTransport;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The central orchestrator for the environment adapter — manages MCP client
 * connections, snapshot lifecycle, and state transitions.
 * <p>
 * Corresponds to the {@code EnvManager} class in the design document.
 * Provides the primary API for AgentCore to interact with external environments:
 * <ul>
 *   <li>{@link #execute(Action)} — execute an environment action</li>
 *   <li>{@link #captureSnapshot()} — capture current environment state</li>
 *   <li>{@link #rollbackSnapshot()} — rollback to last good state</li>
 *   <li>{@link #commitSnapshot()} — mark current state as good</li>
 * </ul>
 * <p>
 * Also supports multi-tenancy via the {@code namespace} concept — preventing
 * cross-task environment pollution.
 */
public final class EnvManager {

    private static final System.Logger logger =
        System.getLogger(EnvManager.class.getName());

    /** Registered MCP client connections */
    private final List<McpClient> activeClients = new CopyOnWriteArrayList<>();

    /** Snapshot manager for state tracking and rollback */
    private final SnapshotManager snapshotManager;

    /** Current environment state */
    private final AtomicReference<EnvState> state = new AtomicReference<>(EnvState.DISCONNECTED);

    /** Namespace for multi-tenant isolation */
    private final String namespace;

    /** List of registered environment event listeners */
    private final List<EnvEventListener> eventListeners = new CopyOnWriteArrayList<>();

    /** The most recent snapshot (captured before the current action) */
    private volatile EnvSnapshot currentSnapshot;

    // ========== Constructors ==========

    public EnvManager() {
        this("default", new SnapshotManager());
    }

    public EnvManager(String namespace) {
        this(namespace, new SnapshotManager());
    }

    public EnvManager(String namespace, SnapshotManager snapshotManager) {
        this.namespace = Objects.requireNonNull(namespace, "namespace must not be null");
        this.snapshotManager = Objects.requireNonNull(snapshotManager, "snapshotManager must not be null");
    }

    // ========== Client Management ==========

    /**
     * Register and connect a new MCP client to the environment.
     *
     * @param serverId  unique server identifier
     * @param transport the transport for this connection
     * @return a future that completes when the client is connected and initialized
     */
    public CompletableFuture<McpClient> connectClient(String serverId, McpTransport transport) {
        // Check for duplicate
        if (activeClients.stream().anyMatch(c -> c.serverId().equals(serverId))) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("Client already registered: " + serverId));
        }

        McpClient client = new McpClient(serverId, transport);

        // Set up notification forwarding
        transport.onNotification((method, paramsJson) -> {
            logger.log(System.Logger.Level.DEBUG,
                "MCP notification from {0}: {1}", serverId, method);
            notifyListeners(new EnvEvent(
                EnvEvent.Type.RESOURCE_CHANGED,
                serverId,
                Map.of("method", method, "params", paramsJson),
                Instant.now()
            ));
        });

        state.set(EnvState.INITIALIZING);

        return client.connect()
            .thenApply(v -> {
                activeClients.add(client);
                state.set(EnvState.READY);
                logger.log(System.Logger.Level.INFO,
                    "Client '{0}' connected and ready", serverId);

                // Notify listeners
                notifyListeners(new EnvEvent(
                    EnvEvent.Type.CLIENT_CONNECTED,
                    serverId,
                    Map.of("tools", client.listTools().size(),
                           "resources", client.listResources().size()),
                    Instant.now()
                ));

                return client;
            })
            .exceptionally(e -> {
                state.set(EnvState.DISCONNECTED);
                throw new RuntimeException(
                    "Failed to connect client '" + serverId + "'", e);
            });
    }

    /**
     * Disconnect a specific MCP client.
     *
     * @param serverId the server ID to disconnect
     */
    public void disconnectClient(String serverId) {
        activeClients.stream()
            .filter(c -> c.serverId().equals(serverId))
            .findFirst()
            .ifPresent(client -> {
                client.disconnect();
                activeClients.remove(client);
                logger.log(System.Logger.Level.INFO,
                    "Client '{0}' disconnected", serverId);

                notifyListeners(new EnvEvent(
                    EnvEvent.Type.CLIENT_DISCONNECTED,
                    serverId,
                    Map.of(),
                    Instant.now()
                ));

                if (activeClients.isEmpty()) {
                    state.set(EnvState.DISCONNECTED);
                }
            });
    }

    /**
     * Get an active MCP client by server ID.
     */
    public Optional<McpClient> getClient(String serverId) {
        return activeClients.stream()
            .filter(c -> c.serverId().equals(serverId))
            .findFirst();
    }

    /**
     * Get all active MCP clients.
     */
    public List<McpClient> activeClients() {
        return Collections.unmodifiableList(activeClients);
    }

    // ========== Core Actions ==========

    /**
     * Execute an action against the environment.
     * <p>
     * This is the primary entry point for AgentCore to perform operations.
     * The action is dispatched to the appropriate MCP client based on
     * the action's target.
     *
     * @param action the action to execute
     * @return a future that completes with the action result
     */
    public CompletableFuture<Observation> execute(Action action) {
        Objects.requireNonNull(action, "action must not be null");

        if (!state.get().canExecute()) {
            return CompletableFuture.failedFuture(
                new IllegalStateException(
                    "Cannot execute in state: " + state.get()));
        }

        state.set(EnvState.CAPTURING_SNAPSHOT);

        // Capture snapshot before executing
        currentSnapshot = captureSnapshot();

        state.set(EnvState.EXECUTING);

        return dispatchAction(action)
            .thenApply(result -> {
                // Auditing phase
                state.set(EnvState.AUDITING);

                Observation observation = toObservation(result);

                // Commit state on success
                if (result.isSuccess()) {
                    snapshotManager.save(currentSnapshot);
                    commitSnapshot();
                    state.set(EnvState.READY);
                }

                notifyListeners(new EnvEvent(
                    EnvEvent.Type.ACTION_EXECUTED,
                    action.target(),
                    Map.of("status", result.status().name(),
                           "actionId", action.actionId()),
                    Instant.now()
                ));

                return observation;
            })
            .exceptionally(e -> {
                state.set(EnvState.ROLLING_BACK);
                logger.log(System.Logger.Level.ERROR,
                    "Action execution failed: {0}", e.getMessage());

                // Auto-rollback on failure
                rollbackSnapshot();

                notifyListeners(new EnvEvent(
                    EnvEvent.Type.ACTION_FAILED,
                    action.target(),
                    Map.of("error", e.getMessage(), "actionId", action.actionId()),
                    Instant.now()
                ));

                return Observation.failure(e.getMessage());
            });
    }

    /**
     * Capture a snapshot of the current environment state.
     * <p>
     * Collects resource versions from all active MCP clients and
     * working directory state.
     *
     * @return the captured environment snapshot
     */
    public EnvSnapshot captureSnapshot() {
        var builder = EnvSnapshot.builder()
            .snapshotId(java.util.UUID.randomUUID().toString().substring(0, 8))
            .timestamp(Instant.now())
            .environmentVariables(System.getenv());

        // Collect resource versions from all clients
        Map<String, String> resourceVersions = new java.util.LinkedHashMap<>();
        for (McpClient client : activeClients) {
            for (var resource : client.listResources()) {
                resourceVersions.put(
                    client.serverId() + ":" + resource.uri(),
                    resource.uri() + "@" + Instant.now().toEpochMilli()
                );
            }
        }
        builder.resourceVersions(resourceVersions);

        EnvSnapshot snapshot = builder.build();
        snapshotManager.save(snapshot);

        logger.log(System.Logger.Level.DEBUG,
            "Snapshot captured: {0}", snapshot.snapshotId());
        return snapshot;
    }

    /**
     * Rollback to the most recent stable snapshot.
     * <p>
     * Called when a verification fails or an action produces unacceptable results.
     */
    public void rollbackSnapshot() {
        state.set(EnvState.ROLLING_BACK);

        Optional<EnvSnapshot> target = snapshotManager.rollback();
        if (target.isPresent()) {
            EnvSnapshot snap = target.get();
            logger.log(System.Logger.Level.INFO,
                "Rolling back to snapshot: {0} (taken at {1})",
                snap.snapshotId(), snap.timestamp());

            if (snap.hasIrreversibleEffects()) {
                logger.log(System.Logger.Level.WARNING,
                    "Snapshot contains irreversible effects: {0}. "
                    + "Logical compensation may be required.",
                    snap.irreversibleEffects());
            }

            currentSnapshot = snap;
        }

        state.set(EnvState.READY);
    }

    /**
     * Commit the current state as verified good.
     * <p>
     * Called after successful Post-Verify.
     */
    public void commitSnapshot() {
        snapshotManager.commit();
        state.set(EnvState.COMMITTED);
        logger.log(System.Logger.Level.DEBUG, "State committed");

        // Transition back to READY for next action
        state.set(EnvState.READY);
    }

    /**
     * Get the diff between the current snapshot and the last committed snapshot.
     *
     * @return a diff report, or empty if no committed snapshot exists
     */
    public Optional<SnapshotManager.DiffReport> diffSinceLastCommit() {
        Optional<EnvSnapshot> committed = snapshotManager.committedSnapshot();
        if (committed.isEmpty() || currentSnapshot == null) {
            return Optional.empty();
        }
        return Optional.of(SnapshotManager.diff(committed.get(), currentSnapshot));
    }

    // ========== Query Methods ==========

    /**
     * Get all tools registered across all connected MCP clients.
     * <p>
     * Used by alice-tool-gateway to dynamically register tool proxies.
     */
    public List<Tool> allTools() {
        List<Tool> all = new ArrayList<>();
        for (McpClient client : activeClients) {
            for (Tool tool : client.listTools()) {
                all.add(tool);
            }
        }
        return Collections.unmodifiableList(all);
    }

    /**
     * Get the current environment state.
     */
    public EnvState state() {
        return state.get();
    }

    /**
     * Get the namespace (for multi-tenant isolation).
     */
    public String namespace() {
        return namespace;
    }

    /**
     * Get the snapshot manager for direct inspection.
     */
    public SnapshotManager snapshotManager() {
        return snapshotManager;
    }

    // ========== Event Listener ==========

    /**
     * Register a listener for environment events (resource changes, client state, etc.).
     */
    public void addEventListener(EnvEventListener listener) {
        eventListeners.add(Objects.requireNonNull(listener, "listener must not be null"));
    }

    /**
     * Remove a previously registered event listener.
     */
    public void removeEventListener(EnvEventListener listener) {
        eventListeners.remove(listener);
    }

    private void notifyListeners(EnvEvent event) {
        for (EnvEventListener listener : eventListeners) {
            try {
                listener.onEnvEvent(event);
            } catch (Exception e) {
                logger.log(System.Logger.Level.WARNING,
                    "Event listener threw exception: {0}", e.getMessage());
            }
        }
    }

    /**
     * Shutdown all clients and clean up.
     */
    public void shutdown() {
        logger.log(System.Logger.Level.INFO,
            "Shutting down EnvManager (namespace: {0})", namespace);
        for (McpClient client : activeClients) {
            client.disconnect();
        }
        activeClients.clear();
        snapshotManager.clear();
        currentSnapshot = null;
        eventListeners.clear();
        state.set(EnvState.DISCONNECTED);
    }

    // ========== Internal ==========

    /**
     * Internal action record used by EnvManager.
     */
    public record Action(
        String actionId,
        String target,      // serverId:toolName or "env:snapshot"
        String toolName,
        Map<String, Object> parameters,
        ActionType type
    ) {
        public enum ActionType {
            TOOL_CALL,
            READ_RESOURCE,
            CAPTURE_SNAPSHOT,
            SUBSCRIBE
        }

        public static Action toolCall(String target, String toolName, Map<String, Object> params) {
            return new Action(
                java.util.UUID.randomUUID().toString().substring(0, 8),
                target, toolName, params, ActionType.TOOL_CALL
            );
        }

        public static Action readResource(String target, String resourceUri) {
            return new Action(
                java.util.UUID.randomUUID().toString().substring(0, 8),
                target, resourceUri, Map.of("uri", resourceUri), ActionType.READ_RESOURCE
            );
        }
    }

    /**
     * Observation produced by an action execution.
     */
    public record Observation(
        String actionId,
        boolean success,
        String summary,
        String rawData,
        Map<String, Object> metadata,
        Instant timestamp
    ) {
        public static Observation success(String actionId, String summary) {
            return new Observation(actionId, true, summary, null, Map.of(), Instant.now());
        }

        public static Observation failure(String summary) {
            return new Observation("", false, summary, null, Map.of(), Instant.now());
        }
    }

    /**
     * Listener interface for environment events.
     */
    @FunctionalInterface
    public interface EnvEventListener {
        void onEnvEvent(EnvEvent event);
    }

    // ========== Dispatch & Conversion ==========

    private CompletableFuture<ToolResult> dispatchAction(Action action) {
        return switch (action.type()) {
            case TOOL_CALL -> dispatchToolCall(action);
            case READ_RESOURCE -> dispatchResourceRead(action);
            case CAPTURE_SNAPSHOT -> CompletableFuture.completedFuture(
                ToolResult.success("Snapshot captured: " + currentSnapshot.snapshotId()));
            case SUBSCRIBE -> dispatchSubscribe(action);
        };
    }

    private CompletableFuture<ToolResult> dispatchToolCall(Action action) {
        // Find the target client
        String target = action.target();
        String toolName = action.toolName();
        Map<String, Object> params = action.parameters();

        // If target contains ":", split into serverId:toolName
        String serverId = target;
        if (target.contains(":")) {
            String[] parts = target.split(":", 2);
            serverId = parts[0];
        }

        Optional<McpClient> clientOpt = getClient(serverId);
        if (clientOpt.isEmpty()) {
            return CompletableFuture.completedFuture(
                ToolResult.error("No client found for: " + serverId));
        }

        return clientOpt.get().callTool(toolName, params);
    }

    private CompletableFuture<ToolResult> dispatchResourceRead(Action action) {
        String target = action.target();
        String uri = action.toolName(); // reuse field for URI

        Optional<McpClient> clientOpt = getClient(target);
        if (clientOpt.isEmpty()) {
            return CompletableFuture.completedFuture(
                ToolResult.error("No client found for: " + target));
        }

        return clientOpt.get().readResource(uri)
            .thenApply(result -> ToolResult.builder()
                .status(ToolResult.Status.SUCCESS)
                .text(result.text())
                .content(Map.of("uri", result.uri(), "mimeType", result.mimeType()))
                .build());
    }

    private CompletableFuture<ToolResult> dispatchSubscribe(Action action) {
        String target = action.target();
        String uri = action.toolName();

        Optional<McpClient> clientOpt = getClient(target);
        if (clientOpt.isEmpty()) {
            return CompletableFuture.failedFuture(
                new RuntimeException("No client found for: " + target));
        }

        return clientOpt.get().subscribeResource(uri)
            .thenApply(v -> ToolResult.success("Subscribed to: " + uri));
    }

    private Observation toObservation(ToolResult toolResult) {
        return new Observation(
            currentSnapshot != null ? currentSnapshot.snapshotId() : "",
            toolResult.isSuccess(),
            toolResult.isError()
                ? "Error: " + toolResult.error()
                : toolResult.text() != null
                    ? toolResult.text().substring(0, Math.min(200, toolResult.text().length()))
                    : "Action completed",
            toolResult.text(),
            toolResult.content(),
            Instant.now()
        );
    }

    @Override
    public String toString() {
        return "EnvManager{namespace='" + namespace
            + "', state=" + state.get()
            + ", clients=" + activeClients.size()
            + ", snapshots=" + snapshotManager.historySize() + "}";
    }
}
