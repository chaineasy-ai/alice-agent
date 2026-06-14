/*
 * WalCompactorSpec — WalCompactor 压缩清理引擎测试
 *
 * 测试目标：
 *   - compactSession: 基于 Checkpoint lastAppliedMessageId 正确删除旧消息
 *   - minRetentionCount: 保留最近 N 条消息，不因压缩过多而丢失
 *   - 无 Checkpoint 时跳过压缩
 *   - 消息数不足 minRetentionCount 时跳过
 *   - compactAll 扫描所有活跃 session
 *   - 后台调度启动/停止
 *   - 幂等安全性：不会删除未确认的消息
 */
package org.cland.alice.memory.wal

import spock.lang.Specification
import spock.lang.Title

import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

@Title("WalCompactor — WAL 后台压缩与清理引擎")
class WalCompactorSpec extends Specification {

    InMemoryWalStore memStore
    FileWalStore fileStore
    WalCompactor compactor
    ScheduledExecutorService scheduler
    String sid = "test-session"

    def setup() {
        memStore = new InMemoryWalStore()
        scheduler = Executors.newSingleThreadScheduledExecutor { r ->
            def t = new Thread(r, "compactor-test")
            t.setDaemon(true)
            return t
        }
    }

    def cleanup() {
        compactor?.stop()
        scheduler?.shutdownNow()
    }

    // ========== 基础压缩 ==========

    def "compactSession removes messages before checkpoint"() {
        given:
        // 写入 10 条消息
        writeMessages(memStore, sid, 10)
        // 保存 Checkpoint，lastAppliedMessageId = 7
        memStore.saveCheckpoint(
            new Checkpoint(0, sid, 7, "ACTING", [:], null, 0))

        and:
        compactor = new WalCompactor(memStore, scheduler, 999, 0, false)

        when:
        def deleted = compactor.compactSession(sid)

        then:
        deleted == 7
        memStore.messageCount(sid) == 3 // 保留消息 8, 9, 10
    }

    def "compactSession skips session without checkpoint"() {
        given:
        writeMessages(memStore, sid, 10)
        compactor = new WalCompactor(memStore, scheduler, 999, 0, false)

        when:
        def deleted = compactor.compactSession(sid)

        then:
        deleted == 0
        memStore.messageCount(sid) == 10
    }

    def "compactSession skips when message count <= minRetention"() {
        given:
        writeMessages(memStore, sid, 5)
        memStore.saveCheckpoint(new Checkpoint(0, sid, 5, "DONE", [:], null, 0))
        compactor = new WalCompactor(memStore, scheduler, 999, 10, false)

        when:
        def deleted = compactor.compactSession(sid)

        then:
        deleted == 0
        memStore.messageCount(sid) == 5 // 数量不足 min 10，跳过
    }

    // ========== FileWalStore 集成 ==========

    def "FileWalStore compactSession removes WAL file lines"() {
        given:
        def tempDir = Files.createTempDirectory("wal-compact-")
        def store = new FileWalStore(tempDir)
        writeMessages(store, sid, 20)
        store.saveCheckpoint(new Checkpoint(0, sid, 15, "ACTING", [:], null, 0))

        and:
        def c = new WalCompactor(store, scheduler, 999, 0, false)

        when:
        def deleted = c.compactSession(sid)

        then:
        deleted == 15
        store.messageCount(sid) == 5

        cleanup:
        c?.stop()
        store?.clearAll()
        tempDir?.toFile()?.deleteDir()
    }

    // ========== minRetentionCount ==========

    def "compactSession respects minRetentionCount"() {
        given:
        writeMessages(memStore, sid, 100)
        memStore.saveCheckpoint(new Checkpoint(0, sid, 80, "ACTING", [:], null, 0))
        compactor = new WalCompactor(memStore, scheduler, 999, 20, false)

        when:
        def deleted = compactor.compactSession(sid)

        then:
        // 应保留至少 20 条，所以只删 msg 1..80（checkpoint）中的 80 条被限制为 100-20=80
        // lastAppliedId=80, msgCount=100, minRetention=20
        // deleteUpTo = min(80, 100-20=80) = 80
        deleted == 80
        memStore.messageCount(sid) == 20
    }

    def "compactSession with minRetention preserves more than checkpoint would allow"() {
        given:
        writeMessages(memStore, sid, 50)
        // checkpoint 在 40，但 minRetention 要求保留 30 条
        memStore.saveCheckpoint(new Checkpoint(0, sid, 40, "ACTING", [:], null, 0))
        compactor = new WalCompactor(memStore, scheduler, 999, 30, false)

        when:
        def deleted = compactor.compactSession(sid)

        then:
        // deleteUpTo = min(40, 50-30=20) = 20
        deleted == 20
        memStore.messageCount(sid) == 30
    }

    // ========== compactAll ==========

    def "compactAll compacts all active sessions"() {
        given:
        // Use separate writer with per-session tracking
        def writer = { store, sessionId, count ->
            long firstId = store.appendMessage(RawMessage.user(0, sessionId, "msg-1"))
            for (int i = 2; i <= count; i++) {
                store.appendMessage(RawMessage.user(0, sessionId, "msg-${i}"))
            }
            // return the first ID so we can compute checkpoint-relative IDs
            firstId
        }
        def s1first = writer(memStore, "s1", 10)
        def s2first = writer(memStore, "s2", 10)
        // s1 checkpoint at message 8 (relative to s1's first ID)
        memStore.saveCheckpoint(new Checkpoint(0, "s1", s1first + 7, "DONE", [:], null, 0))
        // s2 checkpoint at message 6 (relative to s2's first ID)
        memStore.saveCheckpoint(new Checkpoint(0, "s2", s2first + 5, "DONE", [:], null, 0))
        compactor = new WalCompactor(memStore, scheduler, 999, 0, false)

        when:
        def totalDeleted = compactor.compactAll()

        then:
        totalDeleted == 14 // 8 (s1: upTo = firstId+7 = first 8) + 6 (s2: upTo = firstId+5 = first 6)
        memStore.messageCount("s1") == 2
        memStore.messageCount("s2") == 4
    }

    def "compactAll handles empty store"() {
        given:
        compactor = new WalCompactor(memStore, scheduler, 999, 0, false)

        expect:
        compactor.compactAll() == 0
    }

    // ========== 安全保证 ==========

    def "compactSession never deletes unconfirmed messages"() {
        given:
        writeMessages(memStore, sid, 20)
        // Checkpoint 只确认到 10
        memStore.saveCheckpoint(new Checkpoint(0, sid, 10, "ACTING", [:], null, 0))
        compactor = new WalCompactor(memStore, scheduler, 999, 0, false)

        when:
        compactor.compactSession(sid)

        then:
        // 消息 11..20 必须保留
        def remaining = memStore.getAllMessages(sid)
        remaining.every { it.messageId() > 10 }
    }

    def "compactSession is idempotent"() {
        given:
        writeMessages(memStore, sid, 10)
        memStore.saveCheckpoint(new Checkpoint(0, sid, 5, "DONE", [:], null, 0))
        compactor = new WalCompactor(memStore, scheduler, 999, 0, false)

        when:
        def first = compactor.compactSession(sid)
        def second = compactor.compactSession(sid)

        then:
        first == 5
        second == 0 // 第二次无消息可删
        memStore.messageCount(sid) == 5
    }

    // ========== 后台调度 ==========

    def "start stops lifecycle"() {
        given:
        compactor = new WalCompactor(memStore, scheduler, 999, 0, true)

        expect:
        !compactor.isRunning()

        when:
        compactor.start()

        then:
        compactor.isRunning()

        when:
        compactor.stop()

        then:
        !compactor.isRunning()
    }

    def "disabled compactor does not start"() {
        given:
        compactor = new WalCompactor(memStore, scheduler, 999, 0, false)

        when:
        compactor.start()

        then:
        !compactor.isRunning()
    }

    def "start is idempotent"() {
        given:
        compactor = new WalCompactor(memStore, scheduler, 999, 0, true)
        compactor.start()

        when:
        compactor.start() // 第二次调用不应抛出异常

        then:
        noExceptionThrown()
        compactor.isRunning()
    }

    // ========== 状态查询 ==========

    def "getTotalCompactedMessages accumulates across runs"() {
        given:
        def writer = { store, sessionId, count ->
            long first = store.appendMessage(RawMessage.user(0, sessionId, "msg-1"))
            for (int i = 2; i <= count; i++) {
                store.appendMessage(RawMessage.user(0, sessionId, "msg-${i}"))
            }
            first
        }
        def s1first = writer(memStore, sid, 10)
        def s2first = writer(memStore, "s2", 10)
        memStore.saveCheckpoint(new Checkpoint(0, sid, s1first + 5, "DONE", [:], null, 0))  // 6 deleted
        memStore.saveCheckpoint(new Checkpoint(0, "s2", s2first + 3, "DONE", [:], null, 0)) // 4 deleted
        compactor = new WalCompactor(memStore, scheduler, 999, 0, false)

        when:
        compactor.compactAll()
        compactor.compactAll() // 第二次应无操作

        then:
        compactor.getTotalCompactedMessages() == 10 // 6 + 4
    }

    def "getLastRunTimestamp is updated after compactAll"() {
        given:
        writeMessages(memStore, sid, 5)
        memStore.saveCheckpoint(new Checkpoint(0, sid, 3, "DONE", [:], null, 0))
        compactor = new WalCompactor(memStore, scheduler, 999, 0, false)

        when:
        def before = compactor.getLastRunTimestamp()
        Thread.sleep(2)
        compactor.compactAll()
        def after = compactor.getLastRunTimestamp()

        then:
        before == 0
        after > before
    }

    // ========== 辅助 ==========

    private void writeMessages(WalStore store, String sessionId, int count) {
        for (int i = 1; i <= count; i++) {
            store.appendMessage(RawMessage.user(0, sessionId, "msg-${i}"))
        }
    }
}
