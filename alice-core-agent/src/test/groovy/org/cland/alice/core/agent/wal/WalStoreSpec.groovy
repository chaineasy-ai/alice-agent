package org.cland.alice.core.agent.wal

import spock.lang.Specification
import spock.lang.Subject

/**
 * Spock 测试：验证 InMemoryWalStore 存储层。
 */
class WalStoreSpec extends Specification {

    @Subject
    InMemoryWalStore store

    def setup() {
        store = new InMemoryWalStore()
    }

    // ============================================================
    // RawMessage 存储
    // ============================================================

    def "should append and retrieve a single message"() {
        given:
        def msg = RawMessage.user(0, "s1", "Hello")

        when:
        def id = store.appendMessage(msg)

        then:
        id > 0
        store.messageCount("s1") == 1
        store.getMessage(id).present
        store.getMessage(id).get().content() == "Hello"
    }

    def "should append multiple messages with auto-increment IDs"() {
        when:
        def id1 = store.appendMessage(RawMessage.user(0, "s1", "Hi"))
        def id2 = store.appendMessage(RawMessage.assistant(0, "s1", "Hello!"))
        def id3 = store.appendMessage(RawMessage.user(0, "s1", "Bye"))

        then:
        id1 < id2
        id2 < id3
        store.messageCount("s1") == 3
    }

    def "should batch append messages"() {
        given:
        def messages = [
            RawMessage.user(0, "s1", "Q1"),
            RawMessage.assistant(0, "s1", "A1"),
            RawMessage.user(0, "s1", "Q2")
        ]

        when:
        def lastId = store.appendMessages(messages)

        then:
        lastId > 0
        store.messageCount("s1") == 3
    }

    def "should query messages after a given ID"() {
        given:
        store.appendMessage(RawMessage.user(0, "s1", "Msg1")) // id=1
        store.appendMessage(RawMessage.user(0, "s1", "Msg2")) // id=2
        store.appendMessage(RawMessage.user(0, "s1", "Msg3")) // id=3

        when:
        def after1 = store.getMessagesAfter("s1", 1, 10)

        then:
        after1.size() == 2
        after1[0].content() == "Msg2"
        after1[1].content() == "Msg3"
    }

    def "should return empty list for non-existent session"() {
        expect:
        store.getAllMessages("non-existent").isEmpty()
        store.messageCount("non-existent") == 0
    }

    def "should delete messages up to a given ID"() {
        given:
        store.appendMessage(RawMessage.user(0, "s1", "A")) // id=1
        def id2 = store.appendMessage(RawMessage.user(0, "s1", "B")) // id=2
        store.appendMessage(RawMessage.user(0, "s1", "C")) // id=3

        when:
        def removed = store.deleteMessagesUpTo("s1", id2)

        then:
        removed == 2
        store.messageCount("s1") == 1
        store.getAllMessages("s1")[0].content() == "C"
    }

    // ============================================================
    // Checkpoint 存储
    // ============================================================

    def "should save and retrieve latest checkpoint"() {
        given:
        def cp = new Checkpoint(0, "s1", 5, "PLANNING", ["retry": 0], "{}", 0)

        when:
        def id = store.saveCheckpoint(cp)

        then:
        id > 0
        store.getLatestCheckpoint("s1").present
        store.getLatestCheckpoint("s1").get().stateNode() == "PLANNING"
    }

    def "should overwrite checkpoint for same session"() {
        given:
        store.saveCheckpoint(new Checkpoint(0, "s1", 5, "PLANNING", [:], "", 0))

        when:
        def id2 = store.saveCheckpoint(new Checkpoint(0, "s1", 10, "ACTING", [:], "", 0))

        then:
        store.checkpointCount("s1") == 1  // 覆盖，所以还是1个
        store.getLatestCheckpoint("s1").get().stateNode() == "ACTING"
    }

    def "should return empty optional for non-existent checkpoint"() {
        expect:
        store.getLatestCheckpoint("no-session").empty
    }

    // ============================================================
    // Session 管理
    // ============================================================

    def "should clear a single session"() {
        given:
        store.appendMessage(RawMessage.user(0, "s1", "Hi"))
        store.appendMessage(RawMessage.user(0, "s2", "Hello"))
        store.saveCheckpoint(new Checkpoint(0, "s1", 1, "START", [:], "", 0))

        when:
        store.clearSession("s1")

        then:
        store.messageCount("s1") == 0
        store.getLatestCheckpoint("s1").empty
        store.messageCount("s2") == 1  // s2 不受影响
    }

    def "should clear all sessions"() {
        given:
        store.appendMessage(RawMessage.user(0, "s1", "A"))
        store.appendMessage(RawMessage.user(0, "s2", "B"))
        store.saveCheckpoint(new Checkpoint(0, "s1", 1, "START", [:], "", 0))

        when:
        store.clearAll()

        then:
        store.activeSessionIds().isEmpty()
        store.messageCount("s1") == 0
    }

    def "should list active sessions"() {
        given:
        store.appendMessage(RawMessage.user(0, "s1", "A"))
        store.appendMessage(RawMessage.user(0, "s2", "B"))

        expect:
        store.activeSessionIds().containsAll(["s1", "s2"])
    }
}
