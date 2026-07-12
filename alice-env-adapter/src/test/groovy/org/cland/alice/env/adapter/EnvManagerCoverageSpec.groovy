package org.cland.alice.env.adapter

import org.cland.alice.env.adapter.snapshot.EnvSnapshot
import org.cland.alice.env.adapter.snapshot.SnapshotManager
import org.cland.alice.env.adapter.state.EnvState
import org.cland.alice.tool.gateway.model.ToolResult
import spock.lang.Specification
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * EnvManager 覆盖率补充测试 — 覆盖未测试的 State/Branch 路径
 */
class EnvManagerCoverageSpec extends Specification {

  private String initResponse() {
    return FakeMcpTransport.resultResponse([
      protocolVersion: "2.0",
      capabilities   : [tools: [:], resources: [:], prompts: [:]],
      serverInfo     : [name: "test-server", version: "1.0.0"]
    ])
  }

  private String toolsListResponse(List<Map> tools) {
    return FakeMcpTransport.resultResponse([tools: tools])
  }

  // ====================================================================
  // EnvManager — 补充分支
  // ====================================================================

  def "connectClient with failing transport sets DISCONNECTED"() {
    given:
    def transport = new FakeMcpTransport()
    transport.failOnConnect = true
    def mgr = new EnvManager()

    when:
    mgr.connectClient("broken", transport).get(5, TimeUnit.SECONDS)

    then:
    def ex = thrown(Exception)
    mgr.state() == EnvState.DISCONNECTED

    cleanup:
    mgr.shutdown()
  }

  def "disconnectClient removing last client sets DISCONNECTED"() {
    given:
    def transport = new FakeMcpTransport()
      .respondTo("initialize", initResponse())
      .respondTo("tools/list", toolsListResponse([]))
      .respondTo("resources/list", FakeMcpTransport.resultResponse([resources: []]))
    def mgr = new EnvManager()
    mgr.connectClient("svr", transport).get(5, TimeUnit.SECONDS)

    expect:
    mgr.state() == EnvState.READY

    when:
    mgr.disconnectClient("svr")

    then:
    mgr.state() == EnvState.DISCONNECTED
    mgr.activeClients().isEmpty()

    cleanup:
    mgr.shutdown()
  }

  def "execute with CAPTURE_SNAPSHOT action type"() {
    given:
    def transport = new FakeMcpTransport()
      .respondTo("initialize", initResponse())
      .respondTo("tools/list", toolsListResponse([]))
      .respondTo("resources/list", FakeMcpTransport.resultResponse([resources: []]))

    def mgr = new EnvManager()
    mgr.connectClient("svr", transport).get(5, TimeUnit.SECONDS)

    when:
    def action = new EnvManager.Action("test-id", "svr", null, [:], EnvManager.Action.ActionType.CAPTURE_SNAPSHOT)
    def obs = mgr.execute(action).get(5, TimeUnit.SECONDS)

    then:
    obs.success()

    cleanup:
    mgr.shutdown()
  }

  def "execute with SUBSCRIBE action type"() {
    given:
    def transport = new FakeMcpTransport()
      .respondTo("initialize", initResponse())
      .respondTo("tools/list", toolsListResponse([]))
      .respondTo("resources/list", FakeMcpTransport.resultResponse([resources: []]))
      .respondTo("resources/subscribe", FakeMcpTransport.resultResponse([success: true]))

    def mgr = new EnvManager()
    mgr.connectClient("svr", transport).get(5, TimeUnit.SECONDS)

    when:
    def action = new EnvManager.Action("sub-id", "svr", "file:///test.txt", [:], EnvManager.Action.ActionType.SUBSCRIBE)
    def obs = mgr.execute(action).get(5, TimeUnit.SECONDS)

    then:
    obs.success()

    cleanup:
    mgr.shutdown()
  }

  def "diffSinceLastCommit returns empty when no committed snapshot"() {
    given:
    def mgr = new EnvManager()

    expect:
    mgr.diffSinceLastCommit().isEmpty()

    cleanup:
    mgr.shutdown()
  }

  def "allTools returns unmodifiable list"() {
    given:
    def mgr = new EnvManager()

    expect:
    mgr.allTools().class.name.contains("Unmodifiable")

    cleanup:
    mgr.shutdown()
  }

  def "event listener that throws should not propagate"() {
    given:
    def transport = new FakeMcpTransport()
      .respondTo("initialize", initResponse())
      .respondTo("tools/list", toolsListResponse([ [name: "noop"] ]))
      .respondTo("resources/list", FakeMcpTransport.resultResponse([resources: []]))
      .respondTo("tools/call", FakeMcpTransport.resultResponse([
        content: [[type: "text", text: "ok"]]
      ]))
    def mgr = new EnvManager()
    mgr.connectClient("svr", transport).get(5, TimeUnit.SECONDS)
    mgr.addEventListener(new EnvManager.EnvEventListener() {
      @Override void onEnvEvent(EnvEvent event) { throw new RuntimeException("listener fail") }
    })

    when:
    def obs = mgr.execute(EnvManager.Action.toolCall("svr", "noop", [:])).get(5, TimeUnit.SECONDS)

    then:
    obs.success()

    cleanup:
    mgr.shutdown()
  }

  def "execute with nonexistent target for TOOL_CALL returns error"() {
    given:
    def mgr = new EnvManager("test")
    // Directly set state to READY to bypass connectClient
    // Use connectClient to get READY state
    def transport = new FakeMcpTransport()
      .respondTo("initialize", initResponse())
      .respondTo("tools/list", toolsListResponse([]))
      .respondTo("resources/list", FakeMcpTransport.resultResponse([resources: []]))
    mgr.connectClient("real-server", transport).get(5, TimeUnit.SECONDS)

    when:
    // Action targeting a non-existent server
    def action = EnvManager.Action.toolCall("nonexistent", "anyTool", [:])
    def obs = mgr.execute(action).get(5, TimeUnit.SECONDS)

    then:
    !obs.success()
    obs.summary().contains("No client found")

    cleanup:
    mgr.shutdown()
  }

  def "shutdown with multiple listeners clears all state"() {
    given:
    def transport = new FakeMcpTransport()
      .respondTo("initialize", initResponse())
      .respondTo("tools/list", toolsListResponse([]))
      .respondTo("resources/list", FakeMcpTransport.resultResponse([resources: []]))
    def mgr = new EnvManager()
    mgr.connectClient("svr", transport).get(5, TimeUnit.SECONDS)
    mgr.addEventListener(new EnvManager.EnvEventListener() {
      @Override void onEnvEvent(EnvEvent e) {}
    })

    when:
    mgr.shutdown()

    then:
    mgr.state() == EnvState.DISCONNECTED
    mgr.activeClients().isEmpty()
    mgr.activeClients().isEmpty()

    cleanup:
    mgr.shutdown()
  }

  // ====================================================================
  // McpClient — 补充分支
  // ====================================================================

  def "McpClient connect when already connected throws"() {
    given:
    def transport = new FakeMcpTransport()
      .respondTo("initialize", initResponse())
      .respondTo("tools/list", toolsListResponse([]))
      .respondTo("resources/list", FakeMcpTransport.resultResponse([resources: []]))
    def client = new McpClient("dup", transport)
    client.connect().get(5, TimeUnit.SECONDS)

    when:
    client.connect().get(5, TimeUnit.SECONDS)

    then:
    thrown(Exception)

    cleanup:
    client.disconnect()
  }

  def "McpClient callTool with null params"() {
    given:
    def transport = new FakeMcpTransport()
      .respondTo("initialize", initResponse())
      .respondTo("tools/list", toolsListResponse([ [name: "test"] ]))
      .respondTo("resources/list", FakeMcpTransport.resultResponse([resources: []]))
      .respondTo("tools/call", FakeMcpTransport.resultResponse([
        content: [[type: "text", text: "result"]]
      ]))
    def client = new McpClient("svr", transport)
    client.connect().get(5, TimeUnit.SECONDS)

    when:
    def result = client.callTool("test", null).get(5, TimeUnit.SECONDS)

    then:
    result.isSuccess()

    cleanup:
    client.disconnect()
  }

  def "McpClient readResource with error returns error"() {
    given:
    def transport = new FakeMcpTransport()
      .respondTo("initialize", initResponse())
      .respondTo("tools/list", toolsListResponse([]))
      .respondTo("resources/list", FakeMcpTransport.resultResponse([resources: [
        [uri: "file:///test.txt", mimeType: "text/plain"]
      ]]))
      .respondTo("resources/read", FakeMcpTransport.errorResponse(-32603, "Read failed"))
    def client = new McpClient("svr", transport)
    client.connect().get(5, TimeUnit.SECONDS)

    when:
    client.readResource("file:///test.txt").get(5, TimeUnit.SECONDS)

    then:
    thrown(Exception)

    cleanup:
    client.disconnect()
  }

  def "McpClient subscribeResource"() {
    given:
    def transport = new FakeMcpTransport()
      .respondTo("initialize", initResponse())
      .respondTo("tools/list", toolsListResponse([]))
      .respondTo("resources/list", FakeMcpTransport.resultResponse([resources: []]))
      .respondTo("resources/subscribe", FakeMcpTransport.resultResponse([success: true]))
    def client = new McpClient("svr", transport)
    client.connect().get(5, TimeUnit.SECONDS)

    when:
    client.subscribeResource("file:///test.txt").get(5, TimeUnit.SECONDS)

    then:
    noExceptionThrown()

    cleanup:
    client.disconnect()
  }

  def "McpClient toString"() {
    given:
    def client = new McpClient("test-id", new FakeMcpTransport())

    expect:
    client.toString().contains("test-id")
    client.toString().contains("DISCONNECTED")
  }
}
