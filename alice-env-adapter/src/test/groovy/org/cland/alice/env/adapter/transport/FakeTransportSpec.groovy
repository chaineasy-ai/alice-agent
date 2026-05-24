package org.cland.alice.env.adapter.transport

import org.cland.alice.env.adapter.FakeMcpTransport
import spock.lang.Specification
import java.util.concurrent.TimeUnit

/**
 * Tests for the FakeMcpTransport itself — verifying it works correctly
 * as a test double before using it in other tests.
 */
class FakeTransportSpec extends Specification {

  def "should connect and disconnect"() {
    given:
    def transport = new FakeMcpTransport()

    expect:
    !transport.isConnected()

    when:
    transport.connect().get(5, TimeUnit.SECONDS)

    then:
    transport.isConnected()

    when:
    transport.disconnect()

    then:
    !transport.isConnected()
  }

  def "should fail on connect when failOnConnect is set"() {
    given:
    def transport = new FakeMcpTransport()
    transport.failOnConnect = true

    when:
    transport.connect().get(5, TimeUnit.SECONDS)

    then:
    thrown(Exception)
    !transport.isConnected()
  }

  def "should respond to registered methods"() {
    given:
    def transport = new FakeMcpTransport()
      .respondTo("test/method", """{"jsonrpc":"2.0","result":{"status":"ok"}}""")

    when:
    transport.connect().get(5, TimeUnit.SECONDS)
    def request = """{"jsonrpc":"2.0","id":1,"method":"test/method","params":{}}"""
    def response = transport.send(request).get(5, TimeUnit.SECONDS)

    then:
    response.contains("ok")
    response.contains('"id":1')
  }

  def "should record sent messages"() {
    given:
    def transport = new FakeMcpTransport()
    transport.connect().get(5, TimeUnit.SECONDS)

    when:
    transport.send("""{"jsonrpc":"2.0","id":1,"method":"m1","params":{}}""").get(5, TimeUnit.SECONDS)
    transport.send("""{"jsonrpc":"2.0","id":2,"method":"m2","params":{}}""").get(5, TimeUnit.SECONDS)

    then:
    transport.sentMessages.size() == 2
    transport.sentMessages[0].contains("m1")
    transport.sentMessages[1].contains("m2")
    transport.getSentCount() == 2
    transport.getLastMessage().contains("m2")
  }

  def "should fail on send when failOnSend is set"() {
    given:
    def transport = new FakeMcpTransport()
    transport.failOnSend = true
    transport.connect().get(5, TimeUnit.SECONDS)

    when:
    transport.send("msg").get(5, TimeUnit.SECONDS)

    then:
    thrown(Exception)
  }

  def "should fail to send when not connected"() {
    given:
    def transport = new FakeMcpTransport()

    when:
    transport.send("msg").get(5, TimeUnit.SECONDS)

    then:
    thrown(Exception)
  }

  def "should support notification listeners"() {
    given:
    def transport = new FakeMcpTransport()
    def received = []
    transport.onNotification { method, params -> received << [method, params] }

    when:
    transport.simulateNotification("test/event", [key: "value"])

    then:
    received.size() == 1
    received[0][0] == "test/event"
    received[0][1].contains("value")
  }

  def "should not fail without notification listener"() {
    given:
    def transport = new FakeMcpTransport()

    when:
    transport.simulateNotification("test/event", [:])

    then:
    noExceptionThrown()
  }

  def "should handle dynamic response handler"() {
    given:
    def transport = new FakeMcpTransport()
      .respondTo("echo", { String req ->
        def gson = new com.google.gson.Gson()
        def msg = gson.fromJson(req, Map.class)
        def id = msg.id
        return """{"jsonrpc":"2.0","id":$id,"result":{"echoed":${gson.toJson(msg.params)}}}"""
      })

    when:
    transport.connect().get(5, TimeUnit.SECONDS)
    def response = transport.send(
      """{"jsonrpc":"2.0","id":42,"method":"echo","params":{"hello":"world"}}"""
    ).get(5, TimeUnit.SECONDS)

    then:
    response.contains("42")
    response.contains("world")
  }
}
