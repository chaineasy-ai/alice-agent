package org.cland.alice.tool.gateway.model

import com.fasterxml.jackson.databind.ObjectMapper
import spock.lang.Specification
import spock.lang.Title

@Title("McpTool — MCP 工具模型单元测试")
class McpToolSpec extends Specification {

    static final MAPPER = new ObjectMapper()

    def "McpTool 应记录 serverId, toolName, description, inputSchema"() {
        given:
        def schema = ["type": "object", "properties": ["path": ["type": "string"]]]
        def invoker = { Map params -> "ok" } as McpTool.ToolInvoker

        when:
        def tool = McpTool.builder()
            .serverId("filesystem")
            .toolName("read")
            .description("Read file contents")
            .inputSchema(schema)
            .invoker(invoker)
            .build()

        then:
        tool.serverId() == "filesystem"
        tool.toolName() == "read"
        tool.description() == "Read file contents"
        tool.inputSchema() == schema
        tool.qualifiedName() == "filesystem:read"
    }

    def "McpTool.qualifiedName() 应返回 serverId:toolName 格式"() {
        expect:
        McpTool.builder()
            .serverId("github")
            .toolName("list_repos")
            .invoker({ _ -> "ok" })
            .build()
            .qualifiedName() == "github:list_repos"
    }

    def "McpTool.invoke() 应返回 invoker 返回的值"() {
        given:
        def tool = McpTool.builder()
            .serverId("test")
            .toolName("echo")
            .invoker({ Map params -> "hello ${params.text}" as String })
            .build()

        expect:
        tool.invoke([text: "world"]) == "hello world"
    }

    def "McpTool.invoke() 传入 null params 应转为空 Map"() {
        given:
        def tool = McpTool.builder()
            .serverId("test")
            .toolName("noop")
            .invoker({ Map params ->
                assert params != null
                return "ok"
            })
            .build()

        expect:
        tool.invoke(null) == "ok"
    }

    def "McpTool.invoke() 在 invoker 抛异常时应返回错误描述"() {
        given:
        def tool = McpTool.builder()
            .serverId("test")
            .toolName("crash")
            .invoker({ Map params -> throw new RuntimeException("boom") })
            .build()

        expect:
        tool.invoke([:]).contains("MCP tool [test:crash] failed")
        tool.invoke([:]).contains("boom")
    }

    def "McpTool.builder 拒绝 null serverId"() {
        when:
        McpTool.builder()
            .serverId(null)
            .toolName("x")
            .invoker({ _ -> "ok" })
            .build()

        then:
        thrown(NullPointerException)
    }

    def "McpTool.builder 拒绝 null toolName"() {
        when:
        McpTool.builder()
            .serverId("s")
            .toolName(null)
            .invoker({ _ -> "ok" })
            .build()

        then:
        thrown(NullPointerException)
    }

    def "McpTool.builder 拒绝 null invoker"() {
        when:
        McpTool.builder()
            .serverId("s")
            .toolName("t")
            .invoker(null)
            .build()

        then:
        thrown(NullPointerException)
    }

    def "McpTool.toString 应包含 serverId 和 toolName"() {
        given:
        def tool = McpTool.builder()
            .serverId("fs")
            .toolName("list")
            .invoker({ _ -> "ok" })
            .build()

        expect:
        tool.toString().contains("fs")
        tool.toString().contains("list")
        tool.toString().contains("McpTool")
    }

    def "相同字段的两个 McpTool 应视为不等（因为 invoker 不同）"() {
        given:
        def schema = ["type": "object"]
        def a = McpTool.builder()
            .serverId("s").toolName("t")
            .description("desc").inputSchema(schema)
            .invoker({ _ -> "a" })
            .build()
        def b = McpTool.builder()
            .serverId("s").toolName("t")
            .description("desc").inputSchema(schema)
            .invoker({ _ -> "b" })
            .build()

        // McpTool 没有 equals，所以用引用比较
        expect:
        a != b
        a.hashCode() != b.hashCode()
    }

    def "McpTool.inputSchema 返回不可变副本"() {
        given:
        def original = new LinkedHashMap<String, Object>()
        original.put("type", "object")
        def tool = McpTool.builder()
            .serverId("s").toolName("t")
            .inputSchema(original)
            .invoker({ _ -> "ok" })
            .build()

        when:
        tool.inputSchema().put("extra", "value")

        then:
        thrown(UnsupportedOperationException)
    }
}
