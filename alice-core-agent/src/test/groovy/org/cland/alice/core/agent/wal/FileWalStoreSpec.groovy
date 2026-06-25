/*
 * FileWalStoreSpec — 验证 FileWalStore (JSONL 本地文件) 的正确性
 *
 * 测试目标：
 *   - WAL 追加与读取：appendMessage / getAllMessages / getMessagesAfter
 *   - Checkpoint 写入覆盖：saveCheckpoint / getLatestCheckpoint
 *   - WAL 压缩：deleteMessagesUpTo
 *   - Session 管理：clearSession / clearAll / activeSessionIds
 *   - 重建索引：重启后恢复
 *   - 工具调用 round-trip：ToolCall.Function 序列化/反序列化
 */
package org.cland.alice.core.agent.wal

import spock.lang.Specification
import spock.lang.Title

import java.nio.file.Files
import java.nio.file.Path

@Title("FileWalStore — JSONL 本地文件存储 (Jackson)")
class FileWalStoreSpec extends Specification {

    Path tempDir
    FileWalStore store
    String sid = "test-session"

    def setup() {
        tempDir = Files.createTempDirectory("wal-test-")
        store = new FileWalStore(tempDir)
    }

    def cleanup() {
        store?.clearAll()
        tempDir.toFile().deleteDir()
    }

    // ========== WAL 追加与读取 ==========

    def "appendMessage writes to JSONL file"() {
        given:
        def id = store.appendMessage(RawMessage.user(0, sid, "Hello"))

        expect:
        id > 0
        store.messageCount(sid) == 1

        and: "file exists on disk"
        Files.exists(tempDir.resolve("${sid}.wal.jsonl"))
    }

    def "getAllMessages returns all messages in order"() {
        given:
        store.appendMessage(RawMessage.system(0, sid, "System"))
        store.appendMessage(RawMessage.user(0, sid, "User1"))
        store.appendMessage(RawMessage.assistant(0, sid, "Resp1"))

        when:
        def msgs = store.getAllMessages(sid)

        then:
        msgs.size() == 3
        msgs[0].content() == "System"
        msgs[1].content() == "User1"
        msgs[2].content() == "Resp1"
    }

    def "getMessagesAfter returns only newer messages"() {
        given:
        store.appendMessage(RawMessage.user(0, sid, "msg1"))
        store.appendMessage(RawMessage.user(0, sid, "msg2"))
        store.appendMessage(RawMessage.user(0, sid, "msg3"))

        when:
        def after1 = store.getMessagesAfter(sid, 1, 10)

        then:
        after1.size() == 2
        after1.every { it.messageId() > 1 }
    }

    def "appendMessages writes all messages"() {
        given:
        def msgs = [
            RawMessage.user(0, sid, "a"),
            RawMessage.assistant(0, sid, "b"),
            RawMessage.toolResult(0, sid, "tc1", "result")
        ]

        when:
        store.appendMessages(msgs)

        then:
        store.messageCount(sid) == 3
    }

    def "getMessage returns specific message by ID"() {
        given:
        def id1 = store.appendMessage(RawMessage.user(0, sid, "first"))
        def id2 = store.appendMessage(RawMessage.user(0, sid, "second"))

        when:
        def found = store.getMessage(id2)

        then:
        found.present
        found.get().content() == "second"
    }

    def "getMessage returns empty for unknown ID"() {
        expect:
        store.getMessage(999).empty
    }

    // ========== 工具调用 round-trip ==========

    def "tool calls with function details survive round-trip"() {
        given:
        def tc = [ToolCall.of("c1", "get_weather", [city: "Beijing"])]
        def original = RawMessage.assistantWithToolCalls(0, sid, tc)
        store.appendMessage(original)

        when:
        def loaded = store.getAllMessages(sid)

        then:
        loaded.size() == 1
        loaded[0].toolCalls() != null
        loaded[0].toolCalls().size() == 1
        loaded[0].toolCalls()[0].id() == "c1"
        loaded[0].toolCalls()[0].function().name() == "get_weather"
        loaded[0].toolCalls()[0].function().arguments().contains("Beijing")
    }

    def "multiple tool calls round-trip correctly"() {
        given:
        def tc = [
            ToolCall.of("c1", "get_weather", [city: "Shanghai"]),
            ToolCall.of("c2", "get_weather", [city: "Shenzhen"])
        ]
        def original = RawMessage.assistantWithToolCalls(0, sid, tc)
        store.appendMessage(original)

        when:
        def loaded = store.getAllMessages(sid)

        then:
        loaded[0].toolCalls().size() == 2
        loaded[0].toolCalls()[0].id() == "c1"
        loaded[0].toolCalls()[1].id() == "c2"
    }

    // ========== Checkpoint ==========

    def "saveCheckpoint writes to checkpoint file"() {
        given:
        def id = store.saveCheckpoint(
            new Checkpoint(0, sid, 3, "ACTING", ["k": "v"], null, 1000))

        when:
        def loaded = store.getLatestCheckpoint(sid)

        then:
        loaded.present
        loaded.get().stateNode() == "ACTING"
        loaded.get().variableSnapshot() == ["k": "v"]

        and: "file exists on disk"
        Files.exists(tempDir.resolve("${sid}.checkpoint.json"))
    }

    def "saveCheckpoint overwrites existing checkpoint"() {
        given:
        store.saveCheckpoint(new Checkpoint(0, sid, 1, "PLANNING", [:], null, 100))
        store.saveCheckpoint(new Checkpoint(0, sid, 2, "ACTING", [:], null, 200))

        when:
        def loaded = store.getLatestCheckpoint(sid)

        then:
        loaded.present
        loaded.get().stateNode() == "ACTING"
        loaded.get().lastAppliedMessageId() == 2
    }

    def "getLatestCheckpoint returns empty for unknown session"() {
        expect:
        store.getLatestCheckpoint("unknown").empty
    }

    def "checkpointCount returns 1 or 0"() {
        expect:
        store.checkpointCount(sid) == 0

        when:
        store.saveCheckpoint(new Checkpoint(0, sid, 0, "START", [:], null, 0))

        then:
        store.checkpointCount(sid) == 1
    }

    // ========== WAL 压缩 ==========

    def "deleteMessagesUpTo removes old messages"() {
        given:
        store.appendMessage(RawMessage.user(0, sid, "old1"))
        store.appendMessage(RawMessage.user(0, sid, "old2"))
        store.appendMessage(RawMessage.user(0, sid, "keep"))

        when:
        def deleted = store.deleteMessagesUpTo(sid, 2)

        then:
        deleted == 2
        store.messageCount(sid) == 1
        store.getAllMessages(sid)[0].content() == "keep"
    }

    def "deleteMessagesUpTo all removes the file"() {
        given:
        store.appendMessage(RawMessage.user(0, sid, "only"))
        store.deleteMessagesUpTo(sid, 1)

        expect:
        store.messageCount(sid) == 0
        !Files.exists(tempDir.resolve("${sid}.wal.jsonl"))
    }

    // ========== Session 管理 ==========

    def "activeSessionIds returns sessions with data"() {
        given:
        store.appendMessage(RawMessage.user(0, "s1", "a"))
        store.appendMessage(RawMessage.user(0, "s2", "b"))

        expect:
        store.activeSessionIds().containsAll(["s1", "s2"])
    }

    def "clearSession removes all files"() {
        given:
        store.appendMessage(RawMessage.user(0, sid, "test"))
        store.saveCheckpoint(new Checkpoint(0, sid, 1, "DONE", [:], null, 0))

        when:
        store.clearSession(sid)

        then:
        store.messageCount(sid) == 0
        store.getLatestCheckpoint(sid).empty
        !Files.exists(tempDir.resolve("${sid}.wal.jsonl"))
        !Files.exists(tempDir.resolve("${sid}.checkpoint.json"))
    }

    def "clearAll removes all data"() {
        given:
        store.appendMessage(RawMessage.user(0, "s1", "a"))
        store.appendMessage(RawMessage.user(0, "s2", "b"))

        when:
        store.clearAll()

        then:
        store.activeSessionIds().isEmpty()
    }

    // ========== 重建索引 ==========

    def "new FileWalStore rebuilds index from existing files"() {
        given:
        store.appendMessage(RawMessage.user(0, "persist-session", "data"))
        store.saveCheckpoint(new Checkpoint(0, "persist-session", 1, "DONE", [:], null, 0))

        def store2 = new FileWalStore(tempDir)

        expect:
        store2.messageCount("persist-session") == 1
        store2.getLatestCheckpoint("persist-session").present
        store2.getLatestCheckpoint("persist-session").get().stateNode() == "DONE"
    }

    def "sequence ID survives restart"() {
        given:
        store.appendMessage(RawMessage.user(0, sid, "msg"))
        def firstId = store.appendMessage(RawMessage.user(0, sid, "msg2"))

        when:
        def store2 = new FileWalStore(tempDir)
        def nextId = store2.appendMessage(RawMessage.user(0, sid, "msg3"))

        then:
        nextId > firstId  // 序列号从旧 store 恢复并递增
    }

    // ========== 字符串表示 ==========

    def "toString contains store info"() {
        expect:
        store.toString().contains("FileWalStore")
        store.toString().contains("sessions")
    }
}
