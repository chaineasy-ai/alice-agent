package org.cland.alice.env.adapter

import org.cland.alice.env.adapter.snapshot.EnvSnapshot
import org.cland.alice.env.adapter.snapshot.SnapshotManager
import org.cland.alice.env.adapter.state.EnvState
import org.cland.alice.tool.gateway.model.Tool
import org.cland.alice.tool.gateway.model.ToolResult
import spock.lang.Specification
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class EnvManagerSpec extends Specification {

  // ========== Helpers ==========

  private String initResponse() {
    return FakeMcpTransport.resultResponse([
      protocolVersion: "2.0",
      capabilities   : [
        tools    : [:],
        resources: [:],
        prompts  : [:]
      ],
      serverInfo     : [name: "test-server", version: "1.0.0"]
    ])
  }

  private String toolsListResponse(List<Map> tools) {
    return FakeMcpTransport.resultResponse([tools: tools])
  }

  private String toolCallResponse(String text, boolean isError = false) {
    return FakeMcpTransport.resultResponse([
      content: [[type: "text", text: text]],
      isError: isError
    ])
  }

  /**
   * Create a connected EnvManager with a fake MCP server that has one tool.
   */
  private EnvManager createConnectedManager(
      String namespace = "test",
      String serverId = "test-server",
      String toolName = "greet",
      String toolDesc = "A greeter",
      Map toolSchema = [type: "object"]) {

    def transport = new FakeMcpTransport()
      .respondTo("initialize", initResponse())
      .respondTo("tools/list", toolsListResponse([
        [name: toolName, description: toolDesc, inputSchema: toolSchema]
      ]))
      .respondTo("resources/list", FakeMcpTransport.resultResponse([resources: []]))

    def envManager = new EnvManager(namespace)
    envManager.connectClient(serverId, transport).get(5, TimeUnit.SECONDS)
    return envManager
  }

  // ========== Constructor & State ==========

  def "should create with default namespace"() {
    when:
    def mgr = new EnvManager()

    then:
    mgr.namespace() == "default"
    mgr.state() == EnvState.DISCONNECTED
    mgr.activeClients().isEmpty()
  }

  def "should create with custom namespace"() {
    when:
    def mgr = new EnvManager("my-ns")

    then:
    mgr.namespace() == "my-ns"
  }

  def "should create with custom SnapshotManager"() {
    given:
    def snapMgr = new SnapshotManager(10)

    when:
    def mgr = new EnvManager("ns", snapMgr)

    then:
    mgr.snapshotManager().is(snapMgr)
  }

  // ========== Client Connection ==========

  def "should connect a client and transition to READY"() {
    given:
    def transport = new FakeMcpTransport()
      .respondTo("initialize", initResponse())
      .respondTo("tools/list", toolsListResponse([]))
      .respondTo("resources/list", FakeMcpTransport.resultResponse([resources: []]))

    def mgr = new EnvManager()

    when:
    def client = mgr.connectClient("svr1", transport).get(5, TimeUnit.SECONDS)

    then:
    client.isReady()
    mgr.state() == EnvState.READY
    mgr.activeClients().size() == 1
    mgr.activeClients()[0].serverId() == "svr1"
  }

  def "should reject duplicate client connection"() {
    given:
    def transport1 = new FakeMcpTransport()
      .respondTo("initialize", initResponse())
      .respondTo("tools/list", toolsListResponse([]))
      .respondTo("resources/list", FakeMcpTransport.resultResponse([resources: []]))
    def transport2 = new FakeMcpTransport()

    def mgr = new EnvManager()
    mgr.connectClient("dup", transport1).get(5, TimeUnit.SECONDS)

    when:
    mgr.connectClient("dup", transport2).get(5, TimeUnit.SECONDS)

    then:
    def ex = thrown(Exception)
    ex.message.contains("already registered")
  }

  def "should disconnect a specific client"() {
    given:
    def transport = new FakeMcpTransport()
      .respondTo("initialize", initResponse())
      .respondTo("tools/list", toolsListResponse([]))
      .respondTo("resources/list", FakeMcpTransport.resultResponse([resources: []]))
    def mgr = new EnvManager()
    mgr.connectClient("to-disconnect", transport).get(5, TimeUnit.SECONDS)

    when:
    mgr.disconnectClient("to-disconnect")

    then:
    mgr.activeClients().isEmpty()
    mgr.state() == EnvState.DISCONNECTED
    !transport.isConnected()
  }

  def "should get a client by ID"() {
    given:
    def transport = new FakeMcpTransport()
      .respondTo("initialize", initResponse())
      .respondTo("tools/list", toolsListResponse([]))
      .respondTo("resources/list", FakeMcpTransport.resultResponse([resources: []]))
    def mgr = new EnvManager()
    mgr.connectClient("my-client", transport).get(5, TimeUnit.SECONDS)

    expect:
    mgr.getClient("my-client").isPresent()
    mgr.getClient("my-client").get().serverId() == "my-client"
    mgr.getClient("nonexistent").isEmpty()
  }

  // ========== Tool Execution ==========

  def "should execute a tool call action"() {
    given:
    def transport = new FakeMcpTransport()
      .respondTo("initialize", initResponse())
      .respondTo("tools/list", toolsListResponse([
        [name: "greet", description: "Greets someone"]
      ]))
      .respondTo("resources/list", FakeMcpTransport.resultResponse([resources: []]))
      .respondTo("tools/call", toolCallResponse("Hello, Alice!"))

    def mgr = new EnvManager()
    mgr.connectClient("greeter", transport).get(5, TimeUnit.SECONDS)

    when:
    def action = EnvManager.Action.toolCall("greeter", "greet", [name: "Alice"])
    def observation = mgr.execute(action).get(5, TimeUnit.SECONDS)

    then:
    observation.success()
    observation.summary().contains("Hello, Alice!")
    mgr.state() == EnvState.READY  // returned to READY after commit

    // Verify the correct JSON-RPC was sent
    transport.lastMessage.contains("tools/call")
    transport.lastMessage.contains("greet")

    cleanup:
    mgr.shutdown()
  }

  def "should fail execution when no client matches target"() {
    given:
    def mgr = createConnectedManager()

    when:
    def action = EnvManager.Action.toolCall("nonexistent-server", "any", [:])
    def observation = mgr.execute(action).get(5, TimeUnit.SECONDS)

    then:
    !observation.success()
    observation.summary().contains("No client found")

    cleanup:
    mgr.shutdown()
  }

  def "should fail execution when not in READY state"() {
    given:
    def mgr = new EnvManager()  // DISCONNECTED

    when:
    def action = EnvManager.Action.toolCall("x", "y", [:])
    mgr.execute(action).get(5, TimeUnit.SECONDS)

    then:
    def ex = thrown(Exception)
    ex.cause instanceof IllegalStateException

    cleanup:
    mgr.shutdown()
  }

  def "should execute a resource read action"() {
    given:
    def transport = new FakeMcpTransport()
      .respondTo("initialize", initResponse())
      .respondTo("tools/list", toolsListResponse([]))
      .respondTo("resources/list", FakeMcpTransport.resultResponse([resources: [
        [uri: "file:///test.txt", mimeType: "text/plain"]
      ]]))
      .respondTo("resources/read", FakeMcpTransport.resultResponse([
        uri: "file:///test.txt",
        mimeType: "text/plain",
        text: "file content"
      ]))

    def mgr = new EnvManager()
    mgr.connectClient("reader", transport).get(5, TimeUnit.SECONDS)

    when:
    def action = EnvManager.Action.readResource("reader", "file:///test.txt")
    def observation = mgr.execute(action).get(5, TimeUnit.SECONDS)

    then:
    observation.success()

    cleanup:
    mgr.shutdown()
  }

  // ========== Snapshot / Rollback ==========

  def "should capture snapshot before execution"() {
    given:
    def transport = new FakeMcpTransport()
      .respondTo("initialize", initResponse())
      .respondTo("tools/list", toolsListResponse([
        [name: "noop"]
      ]))
      .respondTo("resources/list", FakeMcpTransport.resultResponse([resources: []]))
      .respondTo("tools/call", toolCallResponse("done"))

    def mgr = new EnvManager()
    mgr.connectClient("svr", transport).get(5, TimeUnit.SECONDS)

    when:
    def snap = mgr.captureSnapshot()

    then:
    snap.snapshotId() != null
    snap.timestamp() != null
    snap.resourceVersions() != null

    cleanup:
    mgr.shutdown()
  }

  def "should rollback to previous snapshot"() {
    given:
    def transport = new FakeMcpTransport()
      .respondTo("initialize", initResponse())
      .respondTo("tools/list", toolsListResponse([
        [name: "noop"]
      ]))
      .respondTo("resources/list", FakeMcpTransport.resultResponse([resources: []]))
      .respondTo("tools/call", toolCallResponse("done"))

    def mgr = new EnvManager()
    mgr.connectClient("svr", transport).get(5, TimeUnit.SECONDS)

    when:
    def snap1 = mgr.captureSnapshot()
    mgr.captureSnapshot()
    mgr.rollbackSnapshot()

    then:
    mgr.state() == EnvState.READY

    cleanup:
    mgr.shutdown()
  }

  def "should commit snapshot after verification"() {
    given:
    def mgr = new EnvManager()

    when:
    def snap = EnvSnapshot.empty()
    mgr.snapshotManager().save(snap)
    mgr.commitSnapshot()

    then:
    mgr.snapshotManager().committedSnapshot().isPresent()
    mgr.snapshotManager().committedSnapshot().get().snapshotId() == snap.snapshotId()

    cleanup:
    mgr.shutdown()
  }

  def "should diff between snapshots"() {
    given:
    def before = EnvSnapshot.builder()
      .snapshotId("before")
      .resourceVersions([uri1: "v1"])
      .build()
    def after = EnvSnapshot.builder()
      .snapshotId("after")
      .resourceVersions([uri1: "v2", uri2: "v1"])
      .build()

    when:
    def diff = SnapshotManager.diff(before, after)

    then:
    diff.hasChanges()
    diff.entries().size() >= 1  // uri1 changed, uri2 added
  }

  def "should rollback on execution error"() {
    given:
    def transport = new FakeMcpTransport()
      .respondTo("initialize", initResponse())
      .respondTo("tools/list", toolsListResponse([
        [name: "crash"]
      ]))
      .respondTo("resources/list", FakeMcpTransport.resultResponse([resources: []]))
      .respondTo("tools/call", FakeMcpTransport.errorResponse(-32603, "Execution crashed"))

    def mgr = new EnvManager()
    mgr.connectClient("svr", transport).get(5, TimeUnit.SECONDS)

    when:
    def action = EnvManager.Action.toolCall("svr", "crash", [:])
    def result = mgr.execute(action).get(5, TimeUnit.SECONDS)

    then:
    !result.success()
    result.summary().contains("Error")

    cleanup:
    mgr.shutdown()
  }

  // ========== Event Listeners ==========

  def "should notify event listeners on client connect"() {
    given:
    def transport = new FakeMcpTransport()
      .respondTo("initialize", initResponse())
      .respondTo("tools/list", toolsListResponse([]))
      .respondTo("resources/list", FakeMcpTransport.resultResponse([resources: []]))
    def mgr = new EnvManager()
    def receivedEvents = []
    mgr.addEventListener(new EnvManager.EnvEventListener() {
      @Override
      void onEnvEvent(EnvEvent event) {
        receivedEvents << event
      }
    })

    when:
    mgr.connectClient("svr", transport).get(5, TimeUnit.SECONDS)

    then:
    receivedEvents.size() >= 1
    receivedEvents.any { it.type() == EnvEvent.Type.CLIENT_CONNECTED }

    cleanup:
    mgr.shutdown()
  }

  def "should notify on action execution"() {
    given:
    def transport = new FakeMcpTransport()
      .respondTo("initialize", initResponse())
      .respondTo("tools/list", toolsListResponse([
        [name: "noop"]
      ]))
      .respondTo("resources/list", FakeMcpTransport.resultResponse([resources: []]))
      .respondTo("tools/call", toolCallResponse("done"))

    def mgr = new EnvManager()
    mgr.connectClient("svr", transport).get(5, TimeUnit.SECONDS)
    def receivedEvents = []
    mgr.addEventListener(new EnvManager.EnvEventListener() {
      @Override
      void onEnvEvent(EnvEvent event) {
        receivedEvents << event
      }
    })

    when:
    mgr.execute(EnvManager.Action.toolCall("svr", "noop", [:])).get(5, TimeUnit.SECONDS)

    then:
    receivedEvents.any { it.type() == EnvEvent.Type.ACTION_EXECUTED }

    cleanup:
    mgr.shutdown()
  }

  // ========== All Tools ==========

  def "should aggregate tools from all clients"() {
    given:
    def transport1 = new FakeMcpTransport()
      .respondTo("initialize", initResponse())
      .respondTo("tools/list", toolsListResponse([
        [name: "tool_a", description: "Tool A"]
      ]))
      .respondTo("resources/list", FakeMcpTransport.resultResponse([resources: []]))
    def transport2 = new FakeMcpTransport()
      .respondTo("initialize", initResponse())
      .respondTo("tools/list", toolsListResponse([
        [name: "tool_b", description: "Tool B"]
      ]))
      .respondTo("resources/list", FakeMcpTransport.resultResponse([resources: []]))
    def mgr = new EnvManager()
    mgr.connectClient("svr1", transport1).get(5, TimeUnit.SECONDS)
    mgr.connectClient("svr2", transport2).get(5, TimeUnit.SECONDS)

    when:
    def allTools = mgr.allTools()

    then:
    allTools*.name() as Set == ["tool_a", "tool_b"] as Set

    cleanup:
    mgr.shutdown()
  }

  // ========== Shutdown ==========

  def "should shutdown cleanly"() {
    given:
    def transport = new FakeMcpTransport()
      .respondTo("initialize", initResponse())
      .respondTo("tools/list", toolsListResponse([]))
      .respondTo("resources/list", FakeMcpTransport.resultResponse([resources: []]))
    def mgr = new EnvManager()
    mgr.connectClient("svr", transport).get(5, TimeUnit.SECONDS)

    when:
    mgr.shutdown()

    then:
    mgr.state() == EnvState.DISCONNECTED
    mgr.activeClients().isEmpty()
    !transport.isConnected()
  }
}
