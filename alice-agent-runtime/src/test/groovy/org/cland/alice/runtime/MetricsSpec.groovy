package org.cland.alice.runtime

import org.cland.alice.agent.proto.event.StepEvent
import spock.lang.Specification
import spock.lang.Title

import java.time.Instant

/**
 * 测试 {@link Metrics}：宿主快照 → 指标映射 + JVM 指标合理性。
 */
@Title("Metrics — 基础运行指标")
class MetricsSpec extends Specification {

    def "collect 映射宿主持久字段并附 JVM 指标"() {
        given:
        def health = new HostHealth(
                "sess-9", true, 3L, 42L, StepEvent.Usage.zero(),
                Instant.now().minusSeconds(2))

        when:
        def m = Metrics.collect(health)

        then:
        m.sessionId() == "sess-9"
        m.roundActive()
        m.rounds() == 3L
        m.lastRoundMs() == 42L
        m.tokensLastRound() == 0L
        m.uptimeMs() >= 1900L
        m.heapUsedBytes() > 0L
        m.threads() > 0
    }
}
