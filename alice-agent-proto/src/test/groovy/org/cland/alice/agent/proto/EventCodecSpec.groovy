package org.cland.alice.agent.proto

import org.cland.alice.agent.proto.codec.EventCodec
import org.cland.alice.agent.proto.event.StepEvent
import org.cland.alice.agent.proto.event.StepEventType
import org.cland.alice.agent.proto.port.CommandValidationException
import spock.lang.Specification
import spock.lang.Title

import java.time.Instant

/**
 * 测试 {@link EventCodec} 与 {@link StepEvent}：出方向事件帧 v1。
 */
@Title("EventCodec — 协议 v1 事件编解码")
class EventCodecSpec extends Specification {

    static final String SESSION = "s-01"
    static final String TRACE   = "t-123-1789"

    def "StepEvent 往返保真（含 payload/usage/时间）"() {
        given:
        def ts = Instant.parse("2026-09-19T04:00:00Z")
        def usage = new StepEvent.Usage(10L, 2L, 0L, 0L, 12L, 0.0005d)
        def ev = new StepEvent(
                StepEvent.V1, StepEventType.TOOL_CALL, SESSION, TRACE, 2L, ts,
                ["tool": "bash", "toolCallId": "call-1"], usage)

        when:
        def json = EventCodec.encode(ev)
        def back = EventCodec.decode(json)

        then: "帧字段可见"
        json.contains('"v":1')
        json.contains('"type":"TOOL_CALL"')
        json.contains('"seq":2')

        and: "完整等价（record equals）"
        back == ev
        back.payload().tool == "bash"
        back.usage().totalTokens() == 12L
        back.usage().cost() == 0.0005d
        back.ts() == ts
    }

    def "七个事件类型全部可编解码（枚举口径）"() {
        expect: "只增不删不改名；当前 7 类"
        StepEventType.values().collect { it.name() } ==
                ["THOUGHT", "TOOL_CALL", "TOOL_RESULT", "OBSERVE", "SUMMARY", "DONE", "ERROR"]

        and: "逐类往返"
        StepEventType.values().every { t ->
            def ev = StepEvent.of(t, SESSION, TRACE, 0L, [:])
            EventCodec.decode(EventCodec.encode(ev)).type() == t
        }
    }

    def "解码容忍未知字段（向前兼容）"() {
        given:
        def json = '{"v":1,"type":"DONE","sessionId":"s","traceId":"t","seq":9,' +
                '"ts":"2026-09-19T04:00:00Z","payload":{},"futureField":{"a":1}}'

        when:
        def back = EventCodec.decode(json)

        then:
        back.type() == StepEventType.DONE
        back.seq() == 9L
    }

    def "非法 JSON 抛 CommandValidationException"() {
        when:
        EventCodec.decode("{oops")

        then:
        thrown(CommandValidationException)
    }

    def "不支持的事件版本被拒"() {
        when:
        EventCodec.decode('{"v":2,"type":"DONE","sessionId":"s","traceId":"t","seq":0,"ts":"2026-09-19T04:00:00Z"}')

        then:
        def e = thrown(CommandValidationException)
        e.message.contains("v=2")
    }

    // ────────────────────────────────────────────────────────────────────────
    // StepEvent 自校验（契约层不变量）
    // ────────────────────────────────────────────────────────────────────────

    def "type/sessionId/traceId 不得为 null"() {
        when:
        new StepEvent(1, type, sid, tid, 0L, Instant.now(), [:], null)

        then:
        thrown(NullPointerException)

        where:
        type                  | sid       | tid
        null                  | SESSION   | TRACE
        StepEventType.THOUGHT | null      | TRACE
        StepEventType.THOUGHT | SESSION   | null
    }

    def "seq 不得为负"() {
        when:
        new StepEvent(1, StepEventType.DONE, SESSION, TRACE, -1L, Instant.now(), [:], null)

        then:
        thrown(IllegalArgumentException)
    }

    def "usage 计数不得为负"() {
        when:
        new StepEvent.Usage(input, 0L, 0L, 0L, 0L, 0.0d)

        then:
        thrown(IllegalArgumentException)

        where:
        input << [-1L]
    }

    def "payload/usage/ts 为空时取安全缺省"() {
        when:
        def ev = new StepEvent(1, StepEventType.SUMMARY, SESSION, TRACE, 0L, null, null, null)

        then:
        ev.payload().isEmpty()
        ev.usage() == StepEvent.Usage.zero()
        ev.ts() != null
    }
}
