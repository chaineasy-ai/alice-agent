package org.cland.alice.agent.command

import spock.lang.Specification
import spock.lang.Subject
import spock.lang.Title

/**
 * 测试 {@link AgentCommand#parse(String, String, String)} 对 /sub-agent 子命令的解析。
 *
 * <p>验证所有 7 个子命令的类型映射、参数提取和边界条件。
 */
@Title("SubAgentCmd.parse() 子命令解析")
class SubAgentCmdParseSpec extends Specification {

    static final String SESSION = "sess-01"
    static final String TRACE  = "trace-abc"

    // ========================================================================
    // SpawnSubAgentCmd
    // ========================================================================

    def "/sub-agent spawn 应转换为 SpawnSubAgentCmd"() {
        when:
        def cmd = AgentCommand.parse('/sub-agent spawn --goal "list files in /tmp"', SESSION, TRACE)

        then:
        cmd instanceof SpawnSubAgentCmd
        with(cmd as SpawnSubAgentCmd) {
            goal()   == "list files in /tmp"
            model()  == null
            sessionId() == SESSION
            traceId()   == TRACE
        }
    }

    def "/sub-agent spawn with --model 应选择指定模型"() {
        when:
        def cmd = AgentCommand.parse('/sub-agent spawn --goal "analyze code" --model gpt-4o', SESSION, TRACE)

        then:
        cmd instanceof SpawnSubAgentCmd
        (cmd as SpawnSubAgentCmd).goal()  == "analyze code"
        (cmd as SpawnSubAgentCmd).model() == "gpt-4o"
    }

    def "/sub-agent spawn 缺少 --goal 应返回 null"() {
        expect:
        AgentCommand.parse('/sub-agent spawn', SESSION, TRACE) == null
        AgentCommand.parse('/sub-agent spawn --model gpt-4o', SESSION, TRACE) == null
    }

    def "SpawnSubAgentCmd records 应验证非空约束"() {
        when:
        new SpawnSubAgentCmd(null, SESSION, TRACE)

        then:
        thrown(NullPointerException)
    }

    // ========================================================================
    // ConnectSubAgentCmd
    // ========================================================================

    def "/sub-agent connect 应转换为 ConnectSubAgentCmd"() {
        when:
        def cmd = AgentCommand.parse(
            '/sub-agent connect --name "code-analyzer" --acp-endpoint http://localhost:9000/acp',
            SESSION, TRACE)

        then:
        cmd instanceof ConnectSubAgentCmd
        with(cmd as ConnectSubAgentCmd) {
            name()          == "code-analyzer"
            acpEndpoint().toString() == "http://localhost:9000/acp"
            sessionId() == SESSION
        }
    }

    def "/sub-agent connect 缺少参数应返回 null"() {
        expect:
        AgentCommand.parse('/sub-agent connect --name test', SESSION, TRACE) == null
        AgentCommand.parse('/sub-agent connect --acp-endpoint http://x', SESSION, TRACE) == null
        AgentCommand.parse('/sub-agent connect', SESSION, TRACE) == null
    }

    def "/sub-agent connect 无效 URL 应返回 null"() {
        expect:
        AgentCommand.parse('/sub-agent connect --name x --acp-endpoint not-a-url', SESSION, TRACE) == null
    }

    // ========================================================================
    // ListSubAgentsCmd
    // ========================================================================

    def "/sub-agent list 应转换为 ListSubAgentsCmd"() {
        when:
        def cmd = AgentCommand.parse('/sub-agent list', SESSION, TRACE)

        then:
        cmd instanceof ListSubAgentsCmd
        cmd.sessionId() == SESSION
        cmd.traceId()   == TRACE
    }

    // ========================================================================
    // CancelSubAgentCmd
    // ========================================================================

    def "/sub-agent cancel <id> 应转换为 CancelSubAgentCmd"() {
        when:
        def cmd = AgentCommand.parse('/sub-agent cancel a1b2c3d4', SESSION, TRACE)

        then:
        cmd instanceof CancelSubAgentCmd
        (cmd as CancelSubAgentCmd).subAgentId() == "a1b2c3d4"
    }

    def "/sub-agent cancel 缺少 ID 应返回 null"() {
        expect:
        AgentCommand.parse('/sub-agent cancel', SESSION, TRACE) == null
    }

    // ========================================================================
    // GetSubAgentResultsCmd
    // ========================================================================

    def "/sub-agent results <id> 应转换为 GetSubAgentResultsCmd"() {
        when:
        def cmd = AgentCommand.parse('/sub-agent results a1b2c3d4', SESSION, TRACE)

        then:
        cmd instanceof GetSubAgentResultsCmd
        (cmd as GetSubAgentResultsCmd).subAgentId() == "a1b2c3d4"
    }

    def "/sub-agent results 缺少 ID 应返回 null"() {
        expect:
        AgentCommand.parse('/sub-agent results', SESSION, TRACE) == null
    }

    // ========================================================================
    // SendToSubAgentCmd
    // ========================================================================

    def "/sub-agent send <id> <message> 应转换为 SendToSubAgentCmd"() {
        when:
        def cmd = AgentCommand.parse('/sub-agent send a1b2 "please report status"', SESSION, TRACE)

        then:
        cmd instanceof SendToSubAgentCmd
        with(cmd as SendToSubAgentCmd) {
            subAgentId() == "a1b2"
            message()    == "please report status"
        }
    }

    def "/sub-agent send 缺少 ID 或消息应返回 null"() {
        expect:
        AgentCommand.parse('/sub-agent send', SESSION, TRACE) == null
        AgentCommand.parse('/sub-agent send a1b2', SESSION, TRACE) == null
    }

    // ========================================================================
    // PromptSubAgentCmd
    // ========================================================================

    def "/sub-agent prompt <id> <prompt> 应转换为 PromptSubAgentCmd"() {
        when:
        def cmd = AgentCommand.parse('/sub-agent prompt a1b2 "analyze this code"', SESSION, TRACE)

        then:
        cmd instanceof PromptSubAgentCmd
        with(cmd as PromptSubAgentCmd) {
            subAgentId() == "a1b2"
            prompt()     == "analyze this code"
        }
    }

    def "/sub-agent prompt 缺少 ID 或提示应返回 null"() {
        expect:
        AgentCommand.parse('/sub-agent prompt', SESSION, TRACE) == null
        AgentCommand.parse('/sub-agent prompt a1b2', SESSION, TRACE) == null
    }

    // ========================================================================
    // 未知子命令
    // ========================================================================

    def "未知的 /sub-agent 子命令应返回 null"() {
        expect:
        AgentCommand.parse('/sub-agent unknown', SESSION, TRACE) == null
        AgentCommand.parse('/sub-agent foo bar', SESSION, TRACE) == null
    }
}
