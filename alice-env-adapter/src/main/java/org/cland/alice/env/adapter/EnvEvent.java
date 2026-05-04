package org.cland.alice.env.adapter;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Environment event — emitted by {@link EnvManager} when significant
 * environment state changes occur (client connections, resource changes, etc.).
 * <p>
 * Leverages MCP 2.0's subscription mechanism to detect resource changes
 * and propagate them as events to the AgentCore and other listeners.
 */
public final class EnvEvent {

    /** Event type categories */
    public enum Type {
        /** An MCP client connected successfully */
        CLIENT_CONNECTED,
        /** An MCP client disconnected */
        CLIENT_DISCONNECTED,
        /** A subscribed resource changed (MCP notification) */
        RESOURCE_CHANGED,
        /** An action was executed */
        ACTION_EXECUTED,
        /** An action execution failed */
        ACTION_FAILED,
        /** A snapshot was captured */
        SNAPSHOT_CAPTURED,
        /** A rollback was performed */
        ROLLBACK_PERFORMED,
        /** The environment state was committed */
        STATE_COMMITTED
    }

    private final Type type;
    private final String source;
    private final Map<String, Object> data;
    private final Instant timestamp;

    /**
     * Create a new environment event.
     *
     * @param type      the event type
     * @param source    the source identifier (e.g., server ID, tool name)
     * @param data      additional event data
     * @param timestamp when the event occurred
     */
    public EnvEvent(Type type, String source, Map<String, Object> data, Instant timestamp) {
        this.type = Objects.requireNonNull(type, "type must not be null");
        this.source = source;
        this.data = data != null ? Map.copyOf(data) : Map.of();
        this.timestamp = timestamp != null ? timestamp : Instant.now();
    }

    // ========== Getters ==========

    public Type type()                       { return type; }
    public String source()                   { return source; }
    public Map<String, Object> data()        { return data; }
    public Instant timestamp()               { return timestamp; }

    @Override
    public String toString() {
        return "EnvEvent{type=" + type + ", source='" + source
            + "', timestamp=" + timestamp + "}";
    }
}
