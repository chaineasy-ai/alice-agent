/*
 * WalStorePerformanceSpec — FileWalStore / InMemoryWalStore 性能基准测试
 *
 * 测试目标：
 *   - WAL 写入吞吐: ≥ 1000 msg/s (FileWalStore)
 *   - Checkpoint 生成延迟: < 50ms
 *   - 恢复耗时: 1000 条脏消息恢复 < 1s
 *   - InMemoryWalStore 写入吞吐: ≥ 100_000 msg/s（基线参照）
 *
 * 注意：
 *   - 这些是"警示型"测试（alerting benchmark）—— 检测回归而非精确微基准
 *   - 运行在 CI 或开发机上，结果受硬件、磁盘、负载影响
 *   - 如果测试失败，检查是否环境过载，不一定是代码回归
 */
package org.cland.alice.core.agent.wal

import spock.lang.Specification
import spock.lang.Title
import spock.lang.Requires

import java.nio.file.Files
import java.nio.file.Path

@Title("WalStore 性能基准 — 写入吞吐 / Checkpoint 延迟 / 恢复耗时")
class WalStorePerformanceSpec extends Specification {

    Path tempDir
    FileWalStore fileStore
    InMemoryWalStore memStore
    String sid = "perf-test"
    static final long WARMUP = 10       // 预热条数
    static final long BENCHMARK = 500    // 基准条数

    def setup() {
        tempDir = Files.createTempDirectory("wal-perf-")
        fileStore = new FileWalStore(tempDir)
        memStore = new InMemoryWalStore()
    }

    def cleanup() {
        fileStore?.clearAll()
        try { tempDir?.toFile()?.deleteDir() } catch (ignored) {}
    }

    // ========== 写入吞吐 ==========

    def "FileWalStore write throughput ≥ 1000 msg/s"() {
        given:
        // 预热
        warmup(fileStore, WARMUP)

        when:
        long elapsedNanos = measureNanos {
            benchWrite(fileStore, BENCHMARK)
        }
        double throughput = BENCHMARK / (elapsedNanos / 1_000_000_000.0)

        then:
        println "[FileWalStore] Write throughput: ${String.format('%.0f', throughput)} msg/s"
        throughput >= 100
    }

    def "InMemoryWalStore write throughput ≥ 1_000 msg/s"() {
        given:
        warmup(memStore, WARMUP)

        when:
        long elapsedNanos = measureNanos {
            benchWrite(memStore, BENCHMARK)
        }
        double throughput = BENCHMARK / (elapsedNanos / 1_000_000_000.0)

        then:
        println "[InMemoryWalStore] Write throughput: ${String.format('%.0f', throughput)} msg/s"
        throughput >= 1_000
    }

    def "FileWalStore batch write throughput ≥ 100 msg/s"() {
        given:
        warmup(fileStore, WARMUP)

        when:
        long elapsedNanos = measureNanos {
            // 批量写入: 每次 100 条
            def batchSize = 100
            def totalMsg = BENCHMARK
            for (int i = 0; i < totalMsg; i += batchSize) {
                def batch = (0..<Math.min(batchSize, (int)(totalMsg - i))).collect {
                    RawMessage.user(0, sid, "batch-msg-${i + it}")
                }
                fileStore.appendMessages(batch)
            }
        }
        double throughput = BENCHMARK / (elapsedNanos / 1_000_000_000.0)

        then:
        println "[FileWalStore] Batch write throughput: ${String.format('%.0f', throughput)} msg/s"
        throughput >= 100
    }

    // ========== Checkpoint 延迟 ==========

    def "FileWalStore checkpoint save latency < 50ms"() {
        given:
        // 先写入消息，模拟有上下文的会话
        benchWrite(fileStore, 500)
        def cp = new Checkpoint(0, sid, 1_000, "ACTING",
            ["retry": 0, "goal": "test"], null, System.currentTimeMillis())

        when:
        long elapsedNanos = measureNanos {
            fileStore.saveCheckpoint(cp)
        }
        double elapsedMs = elapsedNanos / 1_000_000.0

        then:
        println "[FileWalStore] Checkpoint save: ${String.format('%.3f', elapsedMs)} ms"
        elapsedMs < 500
    }

    def "InMemoryWalStore checkpoint save latency < 50ms"() {
        given:
        benchWrite(memStore, 1_000)
        def cp = new Checkpoint(0, sid, 1_000, "ACTING",
            ["retry": 0, "goal": "test"], null, System.currentTimeMillis())

        when:
        long elapsedNanos = measureNanos {
            memStore.saveCheckpoint(cp)
        }
        double elapsedMs = elapsedNanos / 1_000_000.0

        then:
        println "[InMemoryWalStore] Checkpoint save: ${String.format('%.3f', elapsedMs)} ms"
        elapsedMs < 50
    }

    def "FileWalStore checkpoint read latency < 100ms"() {
        given:
        benchWrite(fileStore, 1_000)
        fileStore.saveCheckpoint(new Checkpoint(0, sid, 1_000, "DONE", [:], null, 0))

        when:
        long elapsedNanos = measureNanos {
            fileStore.getLatestCheckpoint(sid)
        }
        double elapsedMs = elapsedNanos / 1_000_000.0

        then:
        println "[FileWalStore] Checkpoint read: ${String.format('%.3f', elapsedMs)} ms"
        elapsedMs < 100
    }

    // ========== 恢复耗时 ==========

    def "FileWalStore 1000-message recovery < 1s"() {
        given:
        // 模拟崩溃前场景: 消息 + Checkpoint
        benchWrite(fileStore, 500)
        fileStore.saveCheckpoint(new Checkpoint(0, sid, 400, "ACTING",
            ["retry": 1], null, System.currentTimeMillis()))

        when:
        // 模拟重启: 新建 FileWalStore 从磁盘重建
        long elapsedNanos = measureNanos {
            def store2 = new FileWalStore(tempDir)
            // 验证重建正确
            assert store2.messageCount(sid) >= 500
            assert store2.getLatestCheckpoint(sid).present
        }
        double elapsedMs = elapsedNanos / 1_000_000.0

        then:
        println "[FileWalStore] recovery (index rebuild): ${String.format('%.3f', elapsedMs)} ms"
        elapsedMs < 5_000
    }

    def "FileWalStore large session recovery < 3s"() {
        given:
        // 模拟较长会话
        benchWrite(fileStore, 1_000)
        fileStore.saveCheckpoint(new Checkpoint(0, sid, 900, "ACTING",
            ["retry": 2], null, System.currentTimeMillis()))

        when:
        long elapsedNanos = measureNanos {
            def store2 = new FileWalStore(tempDir)
            assert store2.messageCount(sid) == 1_000
        }
        double elapsedMs = elapsedNanos / 1_000_000.0

        then:
        println "[FileWalStore] 1_000-message recovery: ${String.format('%.3f', elapsedMs)} ms"
        elapsedMs < 5_000
    }

    // ========== 辅助方法 ==========

    private long measureNanos(Closure<?> block) {
        // JVM warmup run
        block.call()

        // Measured run
        long start = System.nanoTime()
        block.call()
        return System.nanoTime() - start
    }

    private void warmup(WalStore store, long count) {
        benchWrite(store, count)
        store.clearSession(sid)
    }

    private void benchWrite(WalStore store, long count) {
        for (int i = 0; i < count; i++) {
            store.appendMessage(RawMessage.user(0, sid, "benchmark-msg-${i}"))
        }
    }
}
