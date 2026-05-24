package org.cland.alice.env.adapter

import org.cland.alice.env.adapter.transport.McpTransport
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * A fake in-memory MCP transport for testing.
 *
 * <p>Allows tests to simulate MCP server responses without starting real processes or HTTP
 * connections. Supports:
 *
 * <ul>
 *   <li>Pre-registering responses for specific JSON-RPC methods
 *   <li>Capturing sent messages for assertion
 *   <li>Simulating server notifications
 *   <li>Simulating connection failures
 * </ul>
 */
class FakeMcpTransport implements McpTransport {

  /** Registered response builders: method name -> (requestJson -> responseJson) */
  final Map<String, Closure<String>> responseHandlers = new LinkedHashMap<>()

  /** All messages sent via this transport (for assertion) */
  final List<String> sentMessages = []

  /** Notifications sent by the "server" */
  NotificationListener notificationListener

  /** Whether the transport is connected */
  boolean connected = false

  /** Simulate a connection failure */
  boolean failOnConnect = false

  /** Simulate send failures */
  boolean failOnSend = false

  // ========== Fluent response configuration ==========

  /**
   * Register a static response for a given method.
   * @param method the JSON-RPC method (e.g., "tools/list")
   * @param responseJson the complete response JSON string
   */
  FakeMcpTransport respondTo(String method, String responseJson) {
    responseHandlers[method] = { String req -> responseJson }
    return this
  }

  /**
   * Register a dynamic response handler for a given method.
   * @param method the JSON-RPC method
   * @param handler a closure that receives the request JSON and returns the response JSON
   */
  FakeMcpTransport respondTo(String method, Closure<String> handler) {
    responseHandlers[method] = handler
    return this
  }

  // ========== Convenience: build responses inline ==========

  /**
   * Build a successful JSON-RPC result response.
   */
  static String resultResponse(Map resultBody) {
    def gson = new com.google.gson.Gson()
    return """{"jsonrpc":"2.0","result":${gson.toJson(resultBody)}}"""
  }

  /**
   * Build a successful JSON-RPC result response with an ID.
   */
  static String resultResponse(int id, Map resultBody) {
    def gson = new com.google.gson.Gson()
    return """{"jsonrpc":"2.0","id":$id,"result":${gson.toJson(resultBody)}}"""
  }

  /**
   * Build an error JSON-RPC response.
   */
  static String errorResponse(int code, String message) {
    return """{"jsonrpc":"2.0","error":{"code":$code,"message":"$message"}}"""
  }

  /**
   * Build a notification JSON (no id field).
   */
  static String notification(String method, Map params = [:]) {
    def gson = new com.google.gson.Gson()
    return """{"jsonrpc":"2.0","method":"$method","params":${gson.toJson(params)}}"""
  }

  // ========== McpTransport implementation ==========

  @Override
  CompletableFuture<Void> connect() {
    if (failOnConnect) {
      return CompletableFuture.failedFuture(new RuntimeException("Simulated connection failure"))
    }
    connected = true
    return CompletableFuture.completedFuture(null)
  }

  @Override
  CompletableFuture<String> send(String message) {
    sentMessages.add(message)

    if (failOnSend) {
      return CompletableFuture.failedFuture(new RuntimeException("Simulated send failure"))
    }

    if (!connected) {
      return CompletableFuture.failedFuture(new IllegalStateException("Transport not connected"))
    }

    // Parse the method from the message
    def gson = new com.google.gson.Gson()
    def msgMap = gson.fromJson(message, Map.class)
    String method = msgMap?.method

    if (method && responseHandlers.containsKey(method)) {
      try {
        String response = responseHandlers[method].call(message)
        // If the response doesn't have an id, inject the request id
        def respMap = gson.fromJson(response, Map.class)
        if (!respMap.containsKey("id") && msgMap.containsKey("id")) {
          respMap["id"] = msgMap["id"]
          response = gson.toJson(respMap)
        }
        return CompletableFuture.completedFuture(response)
      } catch (Exception e) {
        return CompletableFuture.failedFuture(e)
      }
    }

    // Unknown method -> simulate a successful empty response
    def id = msgMap?.id
    if (id != null) {
      return CompletableFuture.completedFuture(
        """{"jsonrpc":"2.0","id":$id,"result":{}}""")
    }

    return CompletableFuture.completedFuture("""{"jsonrpc":"2.0","result":{}}""")
  }

  @Override
  void disconnect() {
    connected = false
  }

  @Override
  boolean isConnected() {
    return connected
  }

  @Override
  void onNotification(NotificationListener listener) {
    this.notificationListener = listener
  }

  /** Simulate an incoming notification from the server. */
  void simulateNotification(String method, Map params = [:]) {
    if (notificationListener != null) {
      def gson = new com.google.gson.Gson()
      notificationListener.onNotification(method, gson.toJson(params))
    }
  }

  /** Get the last sent message */
  String getLastMessage() {
    return sentMessages.isEmpty() ? null : sentMessages.last()
  }

  /** Get the count of sent messages */
  int getSentCount() {
    return sentMessages.size()
  }
}
