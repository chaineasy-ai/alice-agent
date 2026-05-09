package org.cland.alice.env.adapter.transport;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stdio-based MCP transport — communicates with a local MCP server subprocess over its standard
 * input/output streams.
 *
 * <p>Each JSON-RPC message is written as a single line to stdin, and responses are read line by
 * line from stdout.
 */
public final class StdioMcpTransport implements McpTransport {

  private static final Logger logger = LoggerFactory.getLogger(StdioMcpTransport.class);

  private final String command;
  private final String[] args;

  private Process process;
  private Writer stdinWriter;
  private BufferedReader stdoutReader;
  private BufferedReader stderrReader;
  private volatile boolean connected;

  private final AtomicInteger requestId = new AtomicInteger(0);
  private final ConcurrentHashMap<String, CompletableFuture<String>> pendingRequests =
      new ConcurrentHashMap<>();
  private NotificationListener notificationListener;

  private Thread readerThread;
  private Thread stderrThread;

  /**
   * Create a Stdio transport for a given command.
   *
   * @param command the executable command (e.g., "npx", "python", "node")
   * @param args command arguments
   */
  public StdioMcpTransport(String command, String... args) {
    this.command = command;
    this.args = args.clone();
  }

  @Override
  public CompletableFuture<Void> connect() {
    return CompletableFuture.runAsync(
        () -> {
          try {
            ProcessBuilder pb = new ProcessBuilder(command);
            java.util.List<String> cmdList = new java.util.ArrayList<>();
            cmdList.add(command);
            for (String arg : args) {
              cmdList.add(arg);
            }
            pb.command(cmdList);

            process = pb.start();
            stdinWriter =
                new OutputStreamWriter(
                    process.getOutputStream(), java.nio.charset.StandardCharsets.UTF_8);
            stdoutReader =
                new BufferedReader(
                    new InputStreamReader(
                        process.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
            stderrReader =
                new BufferedReader(
                    new InputStreamReader(
                        process.getErrorStream(), java.nio.charset.StandardCharsets.UTF_8));

            // Start reader thread for stdout (responses)
            readerThread = new Thread(this::readLoop, "mcp-stdio-reader");
            readerThread.setDaemon(true);
            readerThread.start();

            // Start reader thread for stderr (logging)
            stderrThread = new Thread(this::readStderrLoop, "mcp-stdio-stderr");
            stderrThread.setDaemon(true);
            stderrThread.start();

            connected = true;
            logger.info("StdioMCP connected: {}", command);
          } catch (Exception e) {
            throw new RuntimeException("Failed to start MCP subprocess: " + command, e);
          }
        });
  }

  @Override
  public CompletableFuture<String> send(String message) {
    if (!connected) {
      return CompletableFuture.failedFuture(new IllegalStateException("Transport not connected"));
    }

    String id = String.valueOf(requestId.incrementAndGet());
    String framed =
        message.replaceFirst("\"jsonrpc\":\\s*\"2.0\"", "\"jsonrpc\":\"2.0\",\"id\":" + id);

    CompletableFuture<String> future = new CompletableFuture<>();
    pendingRequests.put(id, future);

    try {
      synchronized (stdinWriter) {
        stdinWriter.write(framed);
        stdinWriter.write('\n');
        stdinWriter.flush();
      }
      logger.debug("Sent MCP message, id={}", id);
    } catch (Exception e) {
      pendingRequests.remove(id);
      future.completeExceptionally(e);
    }

    return future;
  }

  @Override
  public void disconnect() {
    connected = false;
    if (readerThread != null) {
      readerThread.interrupt();
    }
    if (stderrThread != null) {
      stderrThread.interrupt();
    }
    if (process != null) {
      process.destroy();
      try {
        process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
      } catch (InterruptedException ignored) {
        Thread.currentThread().interrupt();
      }
      process.destroyForcibly();
    }
    // Fail all pending requests
    pendingRequests.forEach(
        (id, future) -> {
          if (!future.isDone()) {
            future.completeExceptionally(new RuntimeException("Transport disconnected"));
          }
        });
    pendingRequests.clear();
    logger.info("StdioMCP disconnected");
  }

  @Override
  public boolean isConnected() {
    return connected && process != null && process.isAlive();
  }

  @Override
  public void onNotification(NotificationListener listener) {
    this.notificationListener = listener;
  }

  // ========== Internal ==========

  private void readLoop() {
    try {
      String line;
      while (connected && (line = stdoutReader.readLine()) != null) {
        processResponse(line);
      }
    } catch (java.io.IOException e) {
      if (connected) {
        logger.error("Stdio read error: {}", e.getMessage());
      }
    } finally {
      connected = false;
    }
  }

  private void readStderrLoop() {
    try {
      String line;
      while ((line = stderrReader.readLine()) != null) {
        logger.debug("MCP stderr: {}", line);
      }
    } catch (java.io.IOException ignored) {
      // subprocess terminated
    }
  }

  @SuppressWarnings("unchecked")
  private void processResponse(String line) {
    try {
      // Parse JSON-RPC response/notification
      var gson = new com.google.gson.Gson();
      var map = gson.fromJson(line, java.util.Map.class);

      if (map == null) return;

      Object idObj = map.get("id");
      if (idObj != null) {
        String id = idObj.toString();
        CompletableFuture<String> future = pendingRequests.remove(id);
        if (future != null) {
          future.complete(line);
        }
      } else if (map.containsKey("method") && notificationListener != null) {
        // It's a notification (no id)
        String method = (String) map.get("method");
        String params = map.get("params") != null ? gson.toJson(map.get("params")) : "{}";
        notificationListener.onNotification(method, params);
      }
    } catch (Exception e) {
      logger.warn("Failed to parse MCP response: {}", e.getMessage());
    }
  }
}
