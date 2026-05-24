package org.cland.alice.env.adapter

import org.cland.alice.env.adapter.transport.McpTransport
import org.cland.alice.env.adapter.transport.StdioMcpTransport
import org.cland.alice.env.adapter.transport.SseMcpTransport
import org.cland.alice.tool.gateway.model.Resource
import org.cland.alice.tool.gateway.model.ResourceResult
import org.cland.alice.tool.gateway.model.Tool
import org.cland.alice.tool.gateway.model.ToolResult
import spock.lang.Specification
import spock.lang.Subject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class McpClientSpec extends Specification {

  // ========== Helpers ==========

  /**
   * Build a standard MCP initialize response (Protocol 2.0).
   */
  private String initResponse() {
    return FakeMcpTransport.resultResponse([
      protocolVersion: "2.0",
      capabilities   : [
        tools    : [:],
        resources: [subscribe: true],
        prompts  : [:]
      ],
      serverInfo     : [
        name   : "test-server",
        version: "1.0.0"
      ]
    ])
  }

  /**
   * Build a tools/list response with given tools.
   */
  private String toolsListResponse(List<Map> tools) {
    return FakeMcpTransport.resultResponse([tools: tools])
  }

  /**
   * Build a resources/list response with given resources.
   */
  private String resourcesListResponse(List<Map> resources) {
    return FakeMcpTransport.resultResponse([resources: resources])
  }

  /**
   * Build a tools/call response with text content.
   */
  private String toolCallResponse(String text, boolean isError = false) {
    return FakeMcpTransport.resultResponse([
      content: [[type: "text", text: text]],
      isError: isError
    ])
  }

  /**
   * Build a resources/read response.
   */
  private String resourceReadResponse(String uri, String mimeType, String text) {
    return FakeMcpTransport.resultResponse([
      uri     : uri,
      mimeType: mimeType,
      text    : text
    ])
  }

  // ========== Tests ==========

  def "should connect and discover capabilities (tools + resources)"() {
    given:
    def transport = new FakeMcpTransport()
      .respondTo("initialize", initResponse())
      .respondTo("tools/list", toolsListResponse([
        [name: "read_file", description: "Read a file", inputSchema: [type: "object"]],
        [name: "write_file", description: "Write a file", inputSchema: [type: "object"]]
      ]))
      .respondTo("resources/list", resourcesListResponse([
        [uri: "file:///data", mimeType: "text/plain", name: "Data File", description: "Data"],
        [uri: "file:///config", mimeType: "application/json", name: "Config"]
      ]))

    def client = new McpClient("test", transport)

    when:
    client.connect().get(5, TimeUnit.SECONDS)

    then:
    client.isReady()
    client.serverId() == "test"
    client.state() == McpClient.ClientState.READY

    // Tools discovered
    client.listTools().size() == 2
    client.listTools()*.name() == ["read_file", "write_file"]
    client.listTools()[0].description() == "Read a file"
    client.listTools()[0].inputSchema() == [type: "object"]

    // Resources discovered
    client.listResources().size() == 2
    client.listResources()*.uri() == ["file:///data", "file:///config"]
    client.listResources()[0].mimeType() == "text/plain"
    client.listResources()[0].name() == "Data File"

    // Capabilities
    client.serverCapabilities().supportsTools()
    client.serverCapabilities().supportsResources()
    client.serverCapabilities().supportsPrompts()

    // Transport reference
    client.transport().is(transport)

    cleanup:
    client.disconnect()
  }

  def "should connect with only tools capability"() {
    given:
    def transport = new FakeMcpTransport()
      .respondTo("initialize", FakeMcpTransport.resultResponse([
        protocolVersion: "2.0",
        capabilities   : [tools: [:]]
      ]))
      .respondTo("tools/list", toolsListResponse([
        [name: "greet", description: "Greets someone"]
      ]))

    def client = new McpClient("minimal", transport)

    when:
    client.connect().get(5, TimeUnit.SECONDS)

    then:
    client.isReady()
    client.listTools().size() == 1
    client.listResources().size() == 0
    client.serverCapabilities().supportsTools()
    !client.serverCapabilities().supportsResources()
    !client.serverCapabilities().supportsPrompts()

    cleanup:
    client.disconnect()
  }

  def "should fail to connect when transport connection fails"() {
    given:
    def transport = new FakeMcpTransport()
    transport.failOnConnect = true
    def client = new McpClient("failing", transport)

    when:
    client.connect().get(5, TimeUnit.SECONDS)

    then:
    def ex = thrown(Exception)
    client.state() == McpClient.ClientState.ERROR
  }

  def "should refuse to connect if not in DISCONNECTED state"() {
    given:
    def transport = new FakeMcpTransport()
      .respondTo("initialize", initResponse())
      .respondTo("tools/list", toolsListResponse([]))
    def client = new McpClient("dup", transport)

    when:
    client.connect().get(5, TimeUnit.SECONDS)
    client.connect().get(5, TimeUnit.SECONDS)

    then:
    def ex = thrown(Exception)
    ex.cause instanceof IllegalStateException
    ex.cause.message.contains("state: READY")
  }

  // ========== Tools ==========

  def "should call a tool and return result"() {
    given:
    def transport = new FakeMcpTransport()
      .respondTo("initialize", initResponse())
      .respondTo("tools/list", toolsListResponse([
        [name: "read_file", description: "Read a file"]
      ]))
      .respondTo("tools/call", toolCallResponse("Hello, World!"))
    def client = new McpClient("caller", transport)
    client.connect().get(5, TimeUnit.SECONDS)

    when:
    def result = client.callTool("read_file", [path: "/test.txt"]).get(5, TimeUnit.SECONDS)

    then:
    result.isSuccess()
    result.text() == "Hello, World!"
    result.status() == ToolResult.Status.SUCCESS
    !result.isError()

    // Verify the JSON-RPC message sent
    transport.lastMessage.contains("tools/call")
    transport.lastMessage.contains("read_file")
    transport.lastMessage.contains("/test.txt")

    cleanup:
    client.disconnect()
  }

  def "should handle tool call error"() {
    given:
    def transport = new FakeMcpTransport()
      .respondTo("initialize", initResponse())
      .respondTo("tools/list", toolsListResponse([
        [name: "failing_tool"]
      ]))
      .respondTo("tools/call", FakeMcpTransport.errorResponse(-32603, "Internal error"))
    def client = new McpClient("error", transport)
    client.connect().get(5, TimeUnit.SECONDS)

    when:
    def result = client.callTool("failing_tool", [:]).get(5, TimeUnit.SECONDS)

    then:
    result.isError()
    result.status() == ToolResult.Status.ERROR
    result.error().contains("Internal error")
    !result.isSuccess()

    cleanup:
    client.disconnect()
  }

  def "should handle tool call returning isError"() {
    given:
    def transport = new FakeMcpTransport()
      .respondTo("initialize", initResponse())
      .respondTo("tools/list", toolsListResponse([
        [name: "validator"]
      ]))
      .respondTo("tools/call", toolCallResponse("Validation failed", true))
    def client = new McpClient("validation", transport)
    client.connect().get(5, TimeUnit.SECONDS)

    when:
    def result = client.callTool("validator", [:]).get(5, TimeUnit.SECONDS)

    then:
    result.isError()
    result.text() == "Validation failed"
    result.status() == ToolResult.Status.ERROR

    cleanup:
    client.disconnect()
  }

  def "should fail tool call if client not ready"() {
    given:
    def client = new McpClient("unconnected", new FakeMcpTransport())

    when:
    client.callTool("any", [:]).get(5, TimeUnit.SECONDS)

    then:
    def ex = thrown(Exception)
    ex.cause instanceof IllegalStateException
  }

  // ========== Resources ==========

  def "should read a resource"() {
    given:
    def transport = new FakeMcpTransport()
      .respondTo("initialize", initResponse())
      .respondTo("tools/list", toolsListResponse([]))
      .respondTo("resources/list", resourcesListResponse([
        [uri: "file:///doc.txt", mimeType: "text/plain"]
      ]))
      .respondTo("resources/read", resourceReadResponse(
        "file:///doc.txt", "text/plain", "File content here"))
    def client = new McpClient("reader", transport)
    client.connect().get(5, TimeUnit.SECONDS)

    when:
    def result = client.readResource("file:///doc.txt").get(5, TimeUnit.SECONDS)

    then:
    result.uri() == "file:///doc.txt"
    result.mimeType() == "text/plain"
    result.text() == "File content here"
    result.sizeBytes() == 17

    cleanup:
    client.disconnect()
  }

  def "should fail resource read when resources not supported"() {
    given:
    def transport = new FakeMcpTransport()
      .respondTo("initialize", FakeMcpTransport.resultResponse([
        protocolVersion: "2.0",
        capabilities   : [tools: [:]]  // no resources
      ]))
      .respondTo("tools/list", toolsListResponse([]))
      // resources/read returns an error because this server doesn't support resources
      .respondTo("resources/read", FakeMcpTransport.errorResponse(-32601, "Method not found"))
    def client = new McpClient("no-resource", transport)

    when:
    client.connect().get(5, TimeUnit.SECONDS)
    client.readResource("file:///test").get(5, TimeUnit.SECONDS)

    then:
    def ex = thrown(Exception)
    ex.cause instanceof RuntimeException
    ex.cause.message.contains("Failed to parse resource result")

    cleanup:
    client.disconnect()
  }

  // ========== Resource subscription ==========

  def "should subscribe to a resource"() {
    given:
    def transport = new FakeMcpTransport()
      .respondTo("initialize", initResponse())
      .respondTo("tools/list", toolsListResponse([]))
      .respondTo("resources/list", resourcesListResponse([]))
      .respondTo("resources/subscribe", """{"jsonrpc":"2.0","result":{}}""")
    def client = new McpClient("subscriber", transport)
    client.connect().get(5, TimeUnit.SECONDS)

    when:
    def result = client.subscribeResource("file:///watch").get(5, TimeUnit.SECONDS)

    then:
    result == null  // subscribe returns void

    cleanup:
    client.disconnect()
  }

  // ========== Notifications ==========

  def "should forward notifications from transport"() {
    given:
    def transport = new FakeMcpTransport()
      .respondTo("initialize", initResponse())
      .respondTo("tools/list", toolsListResponse([]))
      .respondTo("resources/list", resourcesListResponse([]))
    def client = new McpClient("notifier", transport)
    def receivedNotifications = []

    // We test this via EnvManager or by directly checking the transport notification wiring
    // The notification listener is set inside EnvManager.connectClient
    // For McpClient itself, we verify the transport's onNotification works
    transport.onNotification { method, params ->
      receivedNotifications << [method: method, params: params]
    }

    client.connect().get(5, TimeUnit.SECONDS)

    when:
    transport.simulateNotification("notifications/resources/updated",
      [uri: "file:///changed"])

    then:
    receivedNotifications.size() == 1
    receivedNotifications[0].method == "notifications/resources/updated"
    receivedNotifications[0].params.contains("file:///changed")

    cleanup:
    client.disconnect()
  }

  // ========== Attributes ==========

  def "should manage custom attributes"() {
    given:
    def client = new McpClient("attr-test", new FakeMcpTransport())

    when:
    client.attribute("key1", "value1")
    client.attribute("key2", 42)

    then:
    client.attributes()["key1"] == "value1"
    client.attributes()["key2"] == 42
    client.attributes().size() == 2
  }

  // ========== Disconnect ==========

  def "should disconnect and clear state"() {
    given:
    def transport = new FakeMcpTransport()
      .respondTo("initialize", initResponse())
      .respondTo("tools/list", toolsListResponse([
        [name: "tool_a"]
      ]))
      .respondTo("resources/list", resourcesListResponse([
        [uri: "res://a"]
      ]))
    def client = new McpClient("disco", transport)
    client.connect().get(5, TimeUnit.SECONDS)

    when:
    client.disconnect()

    then:
    !client.isReady()
    client.state() == McpClient.ClientState.DISCONNECTED
    client.listTools().isEmpty()
    client.listResources().isEmpty()
    client.serverCapabilities() == null
  }

  // ========== Lifecycle ==========

  def "should go through correct state transitions"() {
    given:
    def transport = new FakeMcpTransport()
      .respondTo("initialize", initResponse())
      .respondTo("tools/list", toolsListResponse([]))
      .respondTo("resources/list", resourcesListResponse([]))
    def client = new McpClient("states", transport)

    expect:
    client.state() == McpClient.ClientState.DISCONNECTED

    when:
    client.connect().get(5, TimeUnit.SECONDS)

    then:
    client.state() == McpClient.ClientState.READY

    when:
    client.disconnect()

    then:
    client.state() == McpClient.ClientState.DISCONNECTED
  }

  // ========== Transport implementations (constructor tests) ==========

  def "should create Stdio transport with command and args"() {
    when:
    def transport = new StdioMcpTransport("node", "server.js", "--port", "3000")

    then:
    !transport.isConnected()  // not started yet
  }

  def "should create SSE transport with endpoint URL"() {
    when:
    def transport = new SseMcpTransport("http://localhost:8080/mcp/sse")

    then:
    !transport.isConnected()
  }

  def "Stdio transport should fail to send before connect"() {
    given:
    def transport = new StdioMcpTransport("nonexistent-cmd")

    when:
    transport.send("{}").get(5, TimeUnit.SECONDS)

    then:
    def ex = thrown(Exception)
    // Should get IllegalStateException because not connected
    ex.cause instanceof IllegalStateException
  }

  // ========== Model types from alice-tool-gateway ==========

  def "should use Tool as MCP wire-format descriptor"() {
    expect:
    Tool.of("test", "A test").name() == "test"
    Tool.of("test", "A test").description() == "A test"
    Tool.builder()
      .name("full")
      .description("Full description")
      .inputSchema([type: "object", properties: [name: [type: "string"]]])
      .build()
      .inputSchema()
      .containsKey("properties")
  }

  def "should use ToolResult as MCP wire-format result"() {
    expect:
    ToolResult.success("ok").isSuccess()
    ToolResult.success("ok").text() == "ok"
    ToolResult.error("fail").isError()
    ToolResult.error("fail").error() == "fail"
    ToolResult.builder()
      .status(ToolResult.Status.SUCCESS)
      .text("done")
      .content([key: "value"])
      .build()
      .content()["key"] == "value"
  }

  def "should use Resource and ResourceResult"() {
    expect:
    Resource.of("file:///test", "text/plain").uri() == "file:///test"
    Resource.builder()
      .uri("file:///test")
      .mimeType("text/plain")
      .name("Test")
      .description("A test resource")
      .build()
      .name() == "Test"

    and:
    ResourceResult.text("file:///res", "text/plain", "content").text() == "content"
    ResourceResult.builder()
      .uri("file:///res")
      .mimeType("text/plain")
      .text("data")
      .data([meta: "info"])
      .build()
      .data()["meta"] == "info"
  }
}
