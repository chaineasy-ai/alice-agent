package org.cland.alice.env.adapter.transport;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * SSE (Server-Sent Events) based MCP transport — communicates with a remote
 * MCP server over HTTP streaming.
 * <p>
 * JSON-RPC requests are sent via HTTP POST, while server notifications and
 * responses are received via an SSE stream.
 */
public final class SseMcpTransport implements McpTransport {

    private static final System.Logger logger =
        System.getLogger(SseMcpTransport.class.getName());

    private final String endpointUrl;
    private final java.util.Map<String, String> headers;

    private volatile boolean connected;
    private final AtomicInteger requestId = new AtomicInteger(0);
    private final ConcurrentHashMap<String, CompletableFuture<String>> pendingRequests =
        new ConcurrentHashMap<>();
    private NotificationListener notificationListener;

    private Thread sseReaderThread;
    private final AtomicReference<String> sessionId = new AtomicReference<>();

    /**
     * Create an SSE transport for a given MCP server endpoint.
     *
     * @param endpointUrl the SSE endpoint URL (e.g., "http://localhost:8080/mcp/sse")
     */
    public SseMcpTransport(String endpointUrl) {
        this.endpointUrl = endpointUrl;
        this.headers = new java.util.HashMap<>();
    }

    /**
     * Add a custom HTTP header for the connection.
     */
    public SseMcpTransport withHeader(String key, String value) {
        this.headers.put(key, value);
        return this;
    }

    @Override
    public CompletableFuture<Void> connect() {
        return CompletableFuture.runAsync(() -> {
            try {
                // Connect to SSE endpoint
                URL url = URI.create(endpointUrl).toURL();
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setDoInput(true);
                conn.setReadTimeout(0); // no timeout for streaming
                headers.forEach(conn::setRequestProperty);

                int responseCode = conn.getResponseCode();
                if (responseCode != 200) {
                    throw new RuntimeException(
                        "SSE connection failed, HTTP " + responseCode);
                }

                connected = true;

                // Start reading SSE stream
                sseReaderThread = new Thread(() -> readSseLoop(conn), "mcp-sse-reader");
                sseReaderThread.setDaemon(true);
                sseReaderThread.start();

                logger.log(System.Logger.Level.INFO,
                    "SSE MCP connected: {0}", endpointUrl);
            } catch (Exception e) {
                throw new RuntimeException(
                    "Failed to connect SSE transport: " + endpointUrl, e);
            }
        });
    }

    @Override
    public CompletableFuture<String> send(String message) {
        if (!connected) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("Transport not connected"));
        }

        String id = String.valueOf(requestId.incrementAndGet());
        // Inject the id into the JSON-RPC message
        String framed = message.replaceFirst(
            "\"jsonrpc\":\\s*\"2.0\"",
            "\"jsonrpc\":\"2.0\",\"id\":" + id);

        CompletableFuture<String> future = new CompletableFuture<>();
        pendingRequests.put(id, future);

        try {
            // For SSE transport, we POST to the message endpoint
            String messageUrl = determineMessageUrl();
            URL url = URI.create(messageUrl).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            if (sessionId.get() != null) {
                conn.setRequestProperty("X-Session-Id", sessionId.get());
            }

            byte[] body = framed.getBytes(StandardCharsets.UTF_8);
            conn.setRequestProperty("Content-Length", String.valueOf(body.length));

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
                os.flush();
            }

            int responseCode = conn.getResponseCode();
            if (responseCode == 200 || responseCode == 202) {
                // Response will come via SSE stream
                logger.log(System.Logger.Level.DEBUG,
                    "Sent MCP message via SSE, id={0}", id);
            } else {
                pendingRequests.remove(id);
                future.completeExceptionally(
                    new RuntimeException("HTTP POST failed: " + responseCode));
            }
        } catch (Exception e) {
            pendingRequests.remove(id);
            future.completeExceptionally(e);
        }

        return future;
    }

    @Override
    public void disconnect() {
        connected = false;
        if (sseReaderThread != null) {
            sseReaderThread.interrupt();
        }
        pendingRequests.forEach((id, future) -> {
            if (!future.isDone()) {
                future.completeExceptionally(
                    new RuntimeException("Transport disconnected"));
            }
        });
        pendingRequests.clear();
        logger.log(System.Logger.Level.INFO, "SSE MCP disconnected");
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public void onNotification(NotificationListener listener) {
        this.notificationListener = listener;
    }

    // ========== Internal ==========

    private void readSseLoop(HttpURLConnection conn) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {

            String line;
            StringBuilder eventData = new StringBuilder();
            String eventType = "message";

            while (connected && (line = reader.readLine()) != null) {
                if (line.startsWith("event:")) {
                    eventType = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    eventData.append(line.substring(5).trim());
                } else if (line.isEmpty()) {
                    // End of event
                    if (!eventData.isEmpty()) {
                        handleSseEvent(eventType, eventData.toString());
                    }
                    eventData = new StringBuilder();
                    eventType = "message";
                }
            }
        } catch (java.io.IOException e) {
            if (connected) {
                logger.log(System.Logger.Level.ERROR,
                    "SSE read error: {0}", e.getMessage());
            }
        } finally {
            connected = false;
        }
    }

    @SuppressWarnings("unchecked")
    private void handleSseEvent(String eventType, String data) {
        try {
            var gson = new com.google.gson.Gson();
            var map = gson.fromJson(data, java.util.Map.class);
            if (map == null) return;

            // Check for session_id in endpoint event
            if ("endpoint".equals(eventType)) {
                sessionId.set((String) map.get("session_id"));
                return;
            }

            Object idObj = map.get("id");
            if (idObj != null) {
                String id = idObj.toString();
                CompletableFuture<String> future = pendingRequests.remove(id);
                if (future != null) {
                    future.complete(data);
                }
            } else if (map.containsKey("method") && notificationListener != null) {
                String method = (String) map.get("method");
                String params = map.get("params") != null
                    ? gson.toJson(map.get("params"))
                    : "{}";
                notificationListener.onNotification(method, params);
            }
        } catch (Exception e) {
            logger.log(System.Logger.Level.WARNING,
                "Failed to parse SSE event: {0}", e.getMessage());
        }
    }

    private String determineMessageUrl() {
        // SSE endpoint usually exposes a message endpoint via the initial handshake.
        // Default convention: replace /sse with /message, or use the endpoint as-is.
        if (endpointUrl.endsWith("/sse")) {
            return endpointUrl.substring(0, endpointUrl.length() - 4) + "/message";
        }
        return endpointUrl;
    }
}
