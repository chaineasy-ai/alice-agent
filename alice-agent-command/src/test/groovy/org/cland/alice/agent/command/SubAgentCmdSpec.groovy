package org.cland.alice.agent.command

import spock.lang.Specification
import spock.lang.Title

import java.time.Instant

/**
 * 测试 {@link SubAgentCmd} 及其 7 个子类型：
 * {@link SpawnSubAgentCmd},
 * {@link ConnectSubAgentCmd},
 * {@link ListSubAgentsCmd},
 * {@link CancelSubAgentCmd},
 * {@link GetSubAgentResultsCmd},
 * {@link SendToSubAgentCmd},
 * {@link PromptSubAgentCmd}.
 *
 * <p>验证每个子类型的构造、Null 安全、Record 相等性、字段访问、toString 等。
 */
@Title("SubAgentCmd 密封接口 — 7 个子类型详细测试")
class SubAgentCmdSpec extends Specification {

    static final String SESSION = "sess-01"
    static final String TRACE  = "trace-xyz"

    // ========================================================================
    // SpawnSubAgentCmd (/sub-agent spawn)
    // ========================================================================

    def "SpawnSubAgentCmd 应记录 goal, model, sessionId, traceId"() {
        given:
        def cmd = new SpawnSubAgentCmd("analyze logs", "gpt-4o", SESSION, TRACE)

        expect:
        cmd.goal()      == "analyze logs"
        cmd.model()     == "gpt-4o"
        cmd.sessionId() == SESSION
        cmd.traceId()   == TRACE
        cmd.timestamp() != null
    }

    def "SpawnSubAgentCmd 以 model=null 创建时应记录 model 为 null"() {
        given:
        def cmd = new SpawnSubAgentCmd("analyze logs", null, SESSION, TRACE)

        expect:
        cmd.goal()  == "analyze logs"
        cmd.model() == null
    }

    def "SpawnSubAgentCmd 双参构造 (goal, sessionId, traceId) 应设 model 为 null"() {
        given:
        def cmd = new SpawnSubAgentCmd("analyze logs", SESSION, TRACE)

        expect:
        cmd.goal()  == "analyze logs"
        cmd.model() == null
        cmd.sessionId() == SESSION
        cmd.traceId()   == TRACE
    }

    def "SpawnSubAgentCmd 可指定自定义时间戳"() {
        given:
        def ts = Instant.parse("2026-06-01T00:00:00Z")
        def cmd = new SpawnSubAgentCmd("task", "gpt-4", SESSION, TRACE, ts)

        expect:
        cmd.timestamp() == ts
    }

    def "SpawnSubAgentCmd target() 应返回 goal"() {
        given:
        def cmd = new SpawnSubAgentCmd("my-goal", SESSION, TRACE)

        expect:
        cmd.target() == "my-goal"
    }

    def "SpawnSubAgentCmd 拒绝 null goal"() {
        when:
        new SpawnSubAgentCmd(null, null, SESSION, TRACE)

        then:
        thrown(NullPointerException)
    }

    def "SpawnSubAgentCmd 拒绝 null sessionId"() {
        when:
        new SpawnSubAgentCmd("goal", null, TRACE)

        then:
        thrown(NullPointerException)
    }

    def "SpawnSubAgentCmd 拒绝 null traceId"() {
        when:
        new SpawnSubAgentCmd("goal", SESSION, null)

        then:
        thrown(NullPointerException)
    }

    def "SpawnSubAgentCmd 拒绝 null timestamp"() {
        when:
        new SpawnSubAgentCmd("goal", null, SESSION, TRACE, null)

        then:
        thrown(NullPointerException)
    }

    // ========================================================================
    // ConnectSubAgentCmd (/sub-agent connect)
    // ========================================================================

    def "ConnectSubAgentCmd 应记录 name, acpEndpoint, sessionId, traceId"() {
        given:
        def uri = URI.create("http://localhost:9000/acp")
        def cmd = new ConnectSubAgentCmd("code-analyzer", uri, SESSION, TRACE)

        expect:
        cmd.name()          == "code-analyzer"
        cmd.acpEndpoint()   == uri
        cmd.sessionId()     == SESSION
        cmd.traceId()       == TRACE
        cmd.timestamp()     != null
    }

    def "ConnectSubAgentCmd 可指定自定义时间戳"() {
        given:
        def ts  = Instant.parse("2026-06-01T01:00:00Z")
        def uri = URI.create("http://localhost:9000/acp")
        def cmd = new ConnectSubAgentCmd("name", uri, SESSION, TRACE, ts)

        expect:
        cmd.timestamp() == ts
    }

    def "ConnectSubAgentCmd target() 应返回 name"() {
        given:
        def uri = URI.create("http://localhost:9000/acp")
        def cmd = new ConnectSubAgentCmd("agent-name", uri, SESSION, TRACE)

        expect:
        cmd.target() == "agent-name"
    }

    def "ConnectSubAgentCmd 接受 https URI"() {
        given:
        def uri = URI.create("https://remote.example.com/acp/v1")
        def cmd = new ConnectSubAgentCmd("remote", uri, SESSION, TRACE)

        expect:
        cmd.acpEndpoint() == uri
    }

    def "ConnectSubAgentCmd 拒绝 null name"() {
        when:
        new ConnectSubAgentCmd(null, URI.create("http://x"), SESSION, TRACE)

        then:
        thrown(NullPointerException)
    }

    def "ConnectSubAgentCmd 拒绝 null acpEndpoint"() {
        when:
        new ConnectSubAgentCmd("name", null, SESSION, TRACE)

        then:
        thrown(NullPointerException)
    }

    def "ConnectSubAgentCmd 拒绝 null sessionId"() {
        when:
        new ConnectSubAgentCmd("name", URI.create("http://x"), null, TRACE)

        then:
        thrown(NullPointerException)
    }

    def "ConnectSubAgentCmd 拒绝 null traceId"() {
        when:
        new ConnectSubAgentCmd("name", URI.create("http://x"), SESSION, null)

        then:
        thrown(NullPointerException)
    }

    def "ConnectSubAgentCmd 拒绝 null timestamp"() {
        when:
        new ConnectSubAgentCmd("name", URI.create("http://x"), SESSION, TRACE, null)

        then:
        thrown(NullPointerException)
    }

    // ========================================================================
    // ListSubAgentsCmd (/sub-agent list)
    // ========================================================================

    def "ListSubAgentsCmd 应记录 sessionId, traceId"() {
        given:
        def cmd = new ListSubAgentsCmd(SESSION, TRACE)

        expect:
        cmd.sessionId() == SESSION
        cmd.traceId()   == TRACE
        cmd.timestamp() != null
    }

    def "ListSubAgentsCmd 可指定自定义时间戳"() {
        given:
        def ts  = Instant.parse("2026-06-01T02:00:00Z")
        def cmd = new ListSubAgentsCmd(SESSION, TRACE, ts)

        expect:
        cmd.timestamp() == ts
    }

    def "ListSubAgentsCmd target() 应返回 'list'"() {
        given:
        def cmd = new ListSubAgentsCmd(SESSION, TRACE)

        expect:
        cmd.target() == "list"
    }

    def "ListSubAgentsCmd 拒绝 null sessionId"() {
        when:
        new ListSubAgentsCmd(null, TRACE)

        then:
        thrown(NullPointerException)
    }

    def "ListSubAgentsCmd 拒绝 null traceId"() {
        when:
        new ListSubAgentsCmd(SESSION, null)

        then:
        thrown(NullPointerException)
    }

    def "ListSubAgentsCmd 拒绝 null timestamp"() {
        when:
        new ListSubAgentsCmd(SESSION, TRACE, null)

        then:
        thrown(NullPointerException)
    }

    // ========================================================================
    // CancelSubAgentCmd (/sub-agent cancel)
    // ========================================================================

    def "CancelSubAgentCmd 应记录 subAgentId, sessionId, traceId"() {
        given:
        def cmd = new CancelSubAgentCmd("sub-agent-id-01", SESSION, TRACE)

        expect:
        cmd.subAgentId() == "sub-agent-id-01"
        cmd.sessionId()  == SESSION
        cmd.traceId()    == TRACE
        cmd.timestamp()  != null
    }

    def "CancelSubAgentCmd 可指定自定义时间戳"() {
        given:
        def ts  = Instant.parse("2026-06-01T03:00:00Z")
        def cmd = new CancelSubAgentCmd("id-01", SESSION, TRACE, ts)

        expect:
        cmd.timestamp() == ts
    }

    def "CancelSubAgentCmd target() 应返回 subAgentId"() {
        given:
        def cmd = new CancelSubAgentCmd("my-id", SESSION, TRACE)

        expect:
        cmd.target() == "my-id"
    }

    def "CancelSubAgentCmd 拒绝 null subAgentId"() {
        when:
        new CancelSubAgentCmd(null, SESSION, TRACE)

        then:
        thrown(NullPointerException)
    }

    def "CancelSubAgentCmd 拒绝 null sessionId"() {
        when:
        new CancelSubAgentCmd("id", null, TRACE)

        then:
        thrown(NullPointerException)
    }

    def "CancelSubAgentCmd 拒绝 null traceId"() {
        when:
        new CancelSubAgentCmd("id", SESSION, null)

        then:
        thrown(NullPointerException)
    }

    def "CancelSubAgentCmd 拒绝 null timestamp"() {
        when:
        new CancelSubAgentCmd("id", SESSION, TRACE, null)

        then:
        thrown(NullPointerException)
    }

    // ========================================================================
    // GetSubAgentResultsCmd (/sub-agent results)
    // ========================================================================

    def "GetSubAgentResultsCmd 应记录 subAgentId, sessionId, traceId"() {
        given:
        def cmd = new GetSubAgentResultsCmd("result-id", SESSION, TRACE)

        expect:
        cmd.subAgentId() == "result-id"
        cmd.sessionId()  == SESSION
        cmd.traceId()    == TRACE
        cmd.timestamp()  != null
    }

    def "GetSubAgentResultsCmd 可指定自定义时间戳"() {
        given:
        def ts  = Instant.parse("2026-06-01T04:00:00Z")
        def cmd = new GetSubAgentResultsCmd("id-01", SESSION, TRACE, ts)

        expect:
        cmd.timestamp() == ts
    }

    def "GetSubAgentResultsCmd target() 应返回 subAgentId"() {
        given:
        def cmd = new GetSubAgentResultsCmd("my-id", SESSION, TRACE)

        expect:
        cmd.target() == "my-id"
    }

    def "GetSubAgentResultsCmd 拒绝 null subAgentId"() {
        when:
        new GetSubAgentResultsCmd(null, SESSION, TRACE)

        then:
        thrown(NullPointerException)
    }

    def "GetSubAgentResultsCmd 拒绝 null sessionId"() {
        when:
        new GetSubAgentResultsCmd("id", null, TRACE)

        then:
        thrown(NullPointerException)
    }

    def "GetSubAgentResultsCmd 拒绝 null traceId"() {
        when:
        new GetSubAgentResultsCmd("id", SESSION, null)

        then:
        thrown(NullPointerException)
    }

    def "GetSubAgentResultsCmd 拒绝 null timestamp"() {
        when:
        new GetSubAgentResultsCmd("id", SESSION, TRACE, null)

        then:
        thrown(NullPointerException)
    }

    // ========================================================================
    // SendToSubAgentCmd (/sub-agent send)
    // ========================================================================

    def "SendToSubAgentCmd 应记录 subAgentId, message, sessionId, traceId"() {
        given:
        def cmd = new SendToSubAgentCmd("id-01", "hello world", SESSION, TRACE)

        expect:
        cmd.subAgentId() == "id-01"
        cmd.message()    == "hello world"
        cmd.sessionId()  == SESSION
        cmd.traceId()    == TRACE
        cmd.timestamp()  != null
    }

    def "SendToSubAgentCmd 可指定自定义时间戳"() {
        given:
        def ts  = Instant.parse("2026-06-01T05:00:00Z")
        def cmd = new SendToSubAgentCmd("id", "msg", SESSION, TRACE, ts)

        expect:
        cmd.timestamp() == ts
    }

    def "SendToSubAgentCmd target() 应返回 subAgentId"() {
        given:
        def cmd = new SendToSubAgentCmd("my-id", "msg", SESSION, TRACE)

        expect:
        cmd.target() == "my-id"
    }

    def "SendToSubAgentCmd 接受空字符串消息"() {
        when:
        def cmd = new SendToSubAgentCmd("id", "", SESSION, TRACE)

        then:
        cmd.message() == ""
    }

    def "SendToSubAgentCmd 拒绝 null subAgentId"() {
        when:
        new SendToSubAgentCmd(null, "msg", SESSION, TRACE)

        then:
        thrown(NullPointerException)
    }

    def "SendToSubAgentCmd 拒绝 null message"() {
        when:
        new SendToSubAgentCmd("id", null, SESSION, TRACE)

        then:
        thrown(NullPointerException)
    }

    def "SendToSubAgentCmd 拒绝 null sessionId"() {
        when:
        new SendToSubAgentCmd("id", "msg", null, TRACE)

        then:
        thrown(NullPointerException)
    }

    def "SendToSubAgentCmd 拒绝 null traceId"() {
        when:
        new SendToSubAgentCmd("id", "msg", SESSION, null)

        then:
        thrown(NullPointerException)
    }

    def "SendToSubAgentCmd 拒绝 null timestamp"() {
        when:
        new SendToSubAgentCmd("id", "msg", SESSION, TRACE, null)

        then:
        thrown(NullPointerException)
    }

    // ========================================================================
    // PromptSubAgentCmd (/sub-agent prompt)
    // ========================================================================

    def "PromptSubAgentCmd 应记录 subAgentId, prompt, sessionId, traceId"() {
        given:
        def cmd = new PromptSubAgentCmd("ext-agent", "analyze this code", SESSION, TRACE)

        expect:
        cmd.subAgentId() == "ext-agent"
        cmd.prompt()     == "analyze this code"
        cmd.sessionId()  == SESSION
        cmd.traceId()    == TRACE
        cmd.timestamp()  != null
    }

    def "PromptSubAgentCmd 可指定自定义时间戳"() {
        given:
        def ts  = Instant.parse("2026-06-01T06:00:00Z")
        def cmd = new PromptSubAgentCmd("id", "prompt", SESSION, TRACE, ts)

        expect:
        cmd.timestamp() == ts
    }

    def "PromptSubAgentCmd target() 应返回 subAgentId"() {
        given:
        def cmd = new PromptSubAgentCmd("my-id", "prompt", SESSION, TRACE)

        expect:
        cmd.target() == "my-id"
    }

    def "PromptSubAgentCmd 接受空字符串 prompt"() {
        when:
        def cmd = new PromptSubAgentCmd("id", "", SESSION, TRACE)

        then:
        cmd.prompt() == ""
    }

    def "PromptSubAgentCmd 拒绝 null subAgentId"() {
        when:
        new PromptSubAgentCmd(null, "prompt", SESSION, TRACE)

        then:
        thrown(NullPointerException)
    }

    def "PromptSubAgentCmd 拒绝 null prompt"() {
        when:
        new PromptSubAgentCmd("id", null, SESSION, TRACE)

        then:
        thrown(NullPointerException)
    }

    def "PromptSubAgentCmd 拒绝 null sessionId"() {
        when:
        new PromptSubAgentCmd("id", "prompt", null, TRACE)

        then:
        thrown(NullPointerException)
    }

    def "PromptSubAgentCmd 拒绝 null traceId"() {
        when:
        new PromptSubAgentCmd("id", "prompt", SESSION, null)

        then:
        thrown(NullPointerException)
    }

    def "PromptSubAgentCmd 拒绝 null timestamp"() {
        when:
        new PromptSubAgentCmd("id", "prompt", SESSION, TRACE, null)

        then:
        thrown(NullPointerException)
    }

    // ========================================================================
    // Record 相等性
    // ========================================================================

    def "相同字段的两个 SpawnSubAgentCmd 应相等"() {
        given:
        def ts = Instant.now()
        def a = new SpawnSubAgentCmd("task", "gpt-4", SESSION, TRACE, ts)
        def b = new SpawnSubAgentCmd("task", "gpt-4", SESSION, TRACE, ts)

        expect:
        a == b
        a.hashCode() == b.hashCode()
    }

    def "不同 goal 的 SpawnSubAgentCmd 不应相等"() {
        expect:
        new SpawnSubAgentCmd("goal-a", SESSION, TRACE) !=
        new SpawnSubAgentCmd("goal-b", SESSION, TRACE)
    }

    def "不同 model 的 SpawnSubAgentCmd 不应相等"() {
        expect:
        new SpawnSubAgentCmd("g", "gpt-4", SESSION, TRACE) !=
        new SpawnSubAgentCmd("g", "claude", SESSION, TRACE)
    }

    def "相同字段的两个 ConnectSubAgentCmd 应相等"() {
        given:
        def ts  = Instant.now()
        def uri = URI.create("http://localhost:9000/acp")
        def a = new ConnectSubAgentCmd("name", uri, SESSION, TRACE, ts)
        def b = new ConnectSubAgentCmd("name", uri, SESSION, TRACE, ts)

        expect:
        a == b
        a.hashCode() == b.hashCode()
    }

    def "不同 URI 的 ConnectSubAgentCmd 不应相等"() {
        expect:
        new ConnectSubAgentCmd("n", URI.create("http://a"), SESSION, TRACE) !=
        new ConnectSubAgentCmd("n", URI.create("http://b"), SESSION, TRACE)
    }

    def "相同字段的两个 CancelSubAgentCmd 应相等"() {
        given:
        def ts = Instant.now()
        def a = new CancelSubAgentCmd("id-01", SESSION, TRACE, ts)
        def b = new CancelSubAgentCmd("id-01", SESSION, TRACE, ts)

        expect:
        a == b
        a.hashCode() == b.hashCode()
    }

    def "不同 subAgentId 的 CancelSubAgentCmd 不应相等"() {
        expect:
        new CancelSubAgentCmd("id-a", SESSION, TRACE) !=
        new CancelSubAgentCmd("id-b", SESSION, TRACE)
    }

    def "相同字段的两个 GetSubAgentResultsCmd 应相等"() {
        given:
        def ts = Instant.now()
        def a = new GetSubAgentResultsCmd("id-01", SESSION, TRACE, ts)
        def b = new GetSubAgentResultsCmd("id-01", SESSION, TRACE, ts)

        expect:
        a == b
        a.hashCode() == b.hashCode()
    }

    def "相同字段的两个 SendToSubAgentCmd 应相等"() {
        given:
        def ts = Instant.now()
        def a = new SendToSubAgentCmd("id", "msg", SESSION, TRACE, ts)
        def b = new SendToSubAgentCmd("id", "msg", SESSION, TRACE, ts)

        expect:
        a == b
        a.hashCode() == b.hashCode()
    }

    def "不同 message 的 SendToSubAgentCmd 不应相等"() {
        expect:
        new SendToSubAgentCmd("id", "msg-a", SESSION, TRACE) !=
        new SendToSubAgentCmd("id", "msg-b", SESSION, TRACE)
    }

    def "相同字段的两个 PromptSubAgentCmd 应相等"() {
        given:
        def ts = Instant.now()
        def a = new PromptSubAgentCmd("id", "prompt", SESSION, TRACE, ts)
        def b = new PromptSubAgentCmd("id", "prompt", SESSION, TRACE, ts)

        expect:
        a == b
        a.hashCode() == b.hashCode()
    }

    def "不同 prompt 的 PromptSubAgentCmd 不应相等"() {
        expect:
        new PromptSubAgentCmd("id", "prompt-a", SESSION, TRACE) !=
        new PromptSubAgentCmd("id", "prompt-b", SESSION, TRACE)
    }

    def "相同字段的两个 ListSubAgentsCmd 应相等"() {
        given:
        def ts = Instant.now()
        def a = new ListSubAgentsCmd(SESSION, TRACE, ts)
        def b = new ListSubAgentsCmd(SESSION, TRACE, ts)

        expect:
        a == b
        a.hashCode() == b.hashCode()
    }

    // ========================================================================
    // toString
    // ========================================================================

    def "SpawnSubAgentCmd toString 应包含 goal 和 model"() {
        when:
        def cmd = new SpawnSubAgentCmd("analyze", "gpt-4", SESSION, TRACE)

        then:
        cmd.toString().contains("analyze")
        cmd.toString().contains("gpt-4")
        cmd.toString().contains("SpawnSubAgentCmd")
    }

    def "ConnectSubAgentCmd toString 应包含 name 和 endpoint"() {
        when:
        def cmd = new ConnectSubAgentCmd("my-agent", URI.create("http://x"), SESSION, TRACE)

        then:
        cmd.toString().contains("my-agent")
        cmd.toString().contains("http://x")
        cmd.toString().contains("ConnectSubAgentCmd")
    }

    def "ListSubAgentsCmd toString 应包含类名"() {
        when:
        def cmd = new ListSubAgentsCmd(SESSION, TRACE)

        then:
        cmd.toString().contains("ListSubAgentsCmd")
    }

    def "CancelSubAgentCmd toString 应包含 subAgentId"() {
        when:
        def cmd = new CancelSubAgentCmd("id-abc", SESSION, TRACE)

        then:
        cmd.toString().contains("id-abc")
        cmd.toString().contains("CancelSubAgentCmd")
    }

    def "GetSubAgentResultsCmd toString 应包含 subAgentId"() {
        when:
        def cmd = new GetSubAgentResultsCmd("id-xyz", SESSION, TRACE)

        then:
        cmd.toString().contains("id-xyz")
        cmd.toString().contains("GetSubAgentResultsCmd")
    }

    def "SendToSubAgentCmd toString 应包含 subAgentId 和 message"() {
        when:
        def cmd = new SendToSubAgentCmd("id-01", "hello", SESSION, TRACE)

        then:
        cmd.toString().contains("id-01")
        cmd.toString().contains("hello")
        cmd.toString().contains("SendToSubAgentCmd")
    }

    def "PromptSubAgentCmd toString 应包含 subAgentId 和 prompt"() {
        when:
        def cmd = new PromptSubAgentCmd("ext-01", "analyze", SESSION, TRACE)

        then:
        cmd.toString().contains("ext-01")
        cmd.toString().contains("analyze")
        cmd.toString().contains("PromptSubAgentCmd")
    }

    // ========================================================================
    // 密封层级：所有 7 种记录都是 SubAgentCmd
    // ========================================================================

    def "所有 7 种 SubAgentCmd 子类型都应 instanceof SubAgentCmd 和 AgentCommand"() {
        given:
        def cmds = [
            new SpawnSubAgentCmd("g", SESSION, TRACE),
            new ConnectSubAgentCmd("n", URI.create("http://x"), SESSION, TRACE),
            new ListSubAgentsCmd(SESSION, TRACE),
            new CancelSubAgentCmd("i", SESSION, TRACE),
            new GetSubAgentResultsCmd("i", SESSION, TRACE),
            new SendToSubAgentCmd("i", "m", SESSION, TRACE),
            new PromptSubAgentCmd("i", "p", SESSION, TRACE),
        ]

        expect:
        cmds.every { it instanceof SubAgentCmd }
        cmds.every { it instanceof AgentCommand }
    }
}
