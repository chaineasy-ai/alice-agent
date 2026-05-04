package org.cland.alice.env.adapter.transport;

import java.util.concurrent.CompletableFuture;

/**
 * Abstract transport layer for MCP 2.0 protocol communication.
 * <p>
 * Supports two modes:
 * <ul>
 *   <li><b>Stdio</b>: local subprocess communication (e.g., Python/Node tools via stdio)</li>
 *   <li><b>SSE</b>: remote server communication via Server-Sent Events streaming</li>
 * </ul>
 * <p>
 * Transport lifecycle: {@link #connect()} → send/receive messages → {@link #disconnect()}.
 */
public interface McpTransport {

    /**
     * Connect to the remote MCP server.
     * <p>
     * For Stdio, this spawns a subprocess and connects to its stdio.
     * For SSE, this establishes an HTTP/SSE connection.
     *
     * @return a future that completes when the connection is established
     */
    CompletableFuture<Void> connect();

    /**
     * Send a JSON-RPC message to the MCP server.
     *
     * @param message the JSON-RPC message string
     * @return a future that completes with the response JSON string
     */
    CompletableFuture<String> send(String message);

    /**
     * Disconnect from the MCP server and release all resources.
     */
    void disconnect();

    /**
     * Check whether this transport is currently connected.
     *
     * @return true if connected
     */
    boolean isConnected();

    /**
     * Register a callback for unsolicited notifications from the server
     * (e.g., resource change events).
     *
     * @param listener the notification listener
     */
    void onNotification(NotificationListener listener);

    /**
     * Listener for MCP server notifications (e.g., resource updates).
     */
    @FunctionalInterface
    interface NotificationListener {
        void onNotification(String method, String paramsJson);
    }
}
