package org.cland.alice.env.adapter.transport

import spock.lang.Specification

/**
 * Transport 基础测试 — 覆盖构造器、Builder 方法等无网络依赖的逻辑
 */
class TransportCoverageSpec extends Specification {

  // ====================================================================
  // SseMcpTransport
  // ====================================================================

  def "SseMcpTransport should be constructable with endpoint URL"() {
    when:
    def transport = new SseMcpTransport("http://localhost:8080/mcp/sse")

    then:
    !transport.isConnected()
    transport != null
  }

  def "SseMcpTransport withHeader should set headers"() {
    given:
    def transport = new SseMcpTransport("http://localhost:8080/mcp/sse")

    when:
    def result = transport.withHeader("Authorization", "Bearer token123")

    then:
    result.is(transport)  // returns this for chaining
  }

  // ====================================================================
  // StdioMcpTransport
  // ====================================================================

  def "StdioMcpTransport should be constructable with command and args"() {
    when:
    def transport = new StdioMcpTransport("npx", "-y", "mcp-server")

    then:
    !transport.isConnected()
    transport != null
  }

  def "StdioMcpTransport should be constructable with single command"() {
    when:
    def transport = new StdioMcpTransport("python")

    then:
    !transport.isConnected()
    transport != null
  }

  def "StdioMcpTransport should return false for isConnected when not connected"() {
    given:
    def transport = new StdioMcpTransport("echo", "hello")

    expect:
    !transport.isConnected()
  }

  def "StdioMcpTransport disconnect without connect should not throw"() {
    given:
    def transport = new StdioMcpTransport("echo", "test")

    when:
    transport.disconnect()

    then:
    noExceptionThrown()
  }

  def "StdioMcpTransport send without connect should fail"() {
    given:
    def transport = new StdioMcpTransport("echo", "test")

    when:
    transport.send("""{"jsonrpc":"2.0","method":"test"}""").get(1, java.util.concurrent.TimeUnit.SECONDS)

    then:
    thrown(Exception)
  }
}
