package org.cland.alice.agent.proto

import org.cland.alice.agent.proto.codec.CommandCodec
import org.cland.alice.agent.proto.port.CommandValidationException
import spock.lang.Specification
import spock.lang.Title

/**
 * 测试 {@link CommandCodec}：协议 v1 命令编解码（往返/兼容/异常）。
 *
 * 覆盖口径（只增不改）：prompt/exec/steer/abort/new/resume/clear/context/compact/feedback。
 */
@Title("CommandCodec — 协议 v1 命令编解码")
class CommandCodecSpec extends Specification {

    static final String SESSION = "s-01"
    static final String TRACE   = "t-123-1789"

    // ========================================================================
    // 往返
    // ========================================================================

    def "prompt 往返保真"() {
        given:
        def cmd = new ExecutionCmd.AcquireGoalCmd("ping", SESSION, TRACE)

        when:
        def json = CommandCodec.encode(cmd)
        def back = CommandCodec.decode(json)

        then: "信封字段正确"
        json.contains('"v":1')
        json.contains('"type":"prompt"')
        json.contains('"sessionId":"s-01"')

        and: "类型与参数保真"
        back instanceof ExecutionCmd.AcquireGoalCmd
        back.goal() == "ping"
        back.sessionId() == SESSION
        back.traceId() == TRACE
    }

    def "会话控制命令往返（#type）"() {
        when:
        def json = CommandCodec.encode(cmd)
        def back = CommandCodec.decode(json)

        then: "type 正确"
        json.contains('"type":"' + type + '"')

        and: "类型与关键参数保真"
        back.class == cmd.class
        back.sessionId() == SESSION
        back.traceId() == TRACE
        check(back)

        where:
        type       | cmd                                                        | check
        "steer"    | new ControlCmd.SteerCmd("插一句", SESSION, TRACE)            | { it.message() == "插一句" }
        "abort"    | new ControlCmd.AbortCmd(SESSION, TRACE)                     | { true }
        "new"      | new ControlCmd.ResetSessionCmd(SESSION, TRACE)              | { true }
        "resume"   | new ControlCmd.ResumeSessionCmd(SESSION, TRACE, "snap-1")   | { it.snapshotId() == "snap-1" }
        "clear"    | new ControlCmd.ClearContextCmd(SESSION, TRACE)              | { true }
        "context"  | new ControlCmd.ViewContextCmd(SESSION, TRACE)               | { true }
        "compact"  | new ControlCmd.CompactContextCmd(SESSION, TRACE)            | { true }
        "feedback" | new ControlCmd.FeedbackCmd("短一点", SESSION, TRACE)         | { it.message() == "短一点" }
        "exec"     | new ExecutionCmd.ExecuteRawCmd("ls -la", SESSION, TRACE)    | { it.command() == "ls -la" }
    }

    // ========================================================================
    // 兼容与容错
    // ========================================================================

    def "解码容忍未知字段（向前兼容）"() {
        given: "未来版本加的新字段/新 payload 键"
        def json = '{"v":1,"type":"abort","sessionId":"s-01","traceId":"t-1",' +
                '"futureField":123,"payload":{"extra":true}}'

        when:
        def cmd = CommandCodec.decode(json)

        then:
        cmd instanceof ControlCmd.AbortCmd
        cmd.sessionId() == "s-01"
    }

    // ========================================================================
    // 异常路径（→ 协议面 400）
    // ========================================================================

    def "非法 JSON 抛 CommandValidationException"() {
        when:
        CommandCodec.decode("not-a-json")

        then:
        thrown(CommandValidationException)
    }

    def "不支持的协议版本被拒（含可读原因）"() {
        when:
        CommandCodec.decode('{"v":2,"type":"abort","sessionId":"s","traceId":"t"}')

        then:
        def e = thrown(CommandValidationException)
        e.message.contains("v=2")
    }

    def "缺必填字段抛异常（prompt 无 message）"() {
        when:
        CommandCodec.decode('{"v":1,"type":"prompt","sessionId":"s","traceId":"t","payload":{}}')

        then:
        def e = thrown(CommandValidationException)
        e.message.contains("message")
    }

    def "未知命令类型抛异常"() {
        when:
        CommandCodec.decode('{"v":1,"type":"nope","sessionId":"s","traceId":"t"}')

        then:
        def e = thrown(CommandValidationException)
        e.message.contains("nope")
    }

    def "v1 未覆盖的命令编码抛异常（按版本纪律可追加）"() {
        when: "CapabilityCmd 家族尚未纳入 v1 线格式"
        CommandCodec.encode(new CapabilityCmd.ReloadKernelCmd(SESSION, TRACE))

        then:
        def e = thrown(CommandValidationException)
        e.message.contains("暂不支持")
    }
}
