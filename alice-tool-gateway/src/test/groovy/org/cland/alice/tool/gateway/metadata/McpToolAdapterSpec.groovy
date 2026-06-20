package org.cland.alice.tool.gateway.metadata

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import spock.lang.Specification
import spock.lang.Title
import org.cland.alice.tool.gateway.ToolRegistry
import org.cland.alice.tool.gateway.annotation.RiskLevel
import org.cland.alice.tool.gateway.engine.ExecutionEngine
import org.cland.alice.tool.gateway.model.McpTool

@Title("McpToolAdapter — McpTool 转 ToolMetadata 适配器测试")
class McpToolAdapterSpec extends Specification {

    static final MAPPER = new ObjectMapper()

    def "toToolMetadata 应正确转换 McpTool 的所有字段"() {
        given:
        def schema = ["type": "object", "properties": ["path": ["type": "string"]]]
        def tool = McpTool.builder()
            .serverId("filesystem")
            .toolName("read")
            .description("Read file contents")
            .inputSchema(schema)
            .invoker({ Map p -> "content" })
            .build()

        when:
        def meta = McpToolAdapter.toToolMetadata(tool)

        then:
        meta.name() == "filesystem:read"
        meta.description() == "Read file contents"
        meta.riskLevel() == RiskLevel.MEDIUM
        meta.returnType() == String
    }

    def "toToolMetadata 的 inputSchema 应为 JsonNode 类型"() {
        given:
        def tool = McpTool.builder()
            .serverId("s").toolName("t")
            .inputSchema(["type": "object"])
            .invoker({ _ -> "ok" })
            .build()

        when:
        def meta = McpToolAdapter.toToolMetadata(tool)

        then:
        meta.inputSchema() instanceof JsonNode
        meta.inputSchema().get("type").asText() == "object"
    }

    def "toToolMetadata 的 inputSchema 为 null/空时应返回空对象节点"() {
        given:
        def tool1 = McpTool.builder().serverId("s").toolName("t")
            .inputSchema(null).invoker({ _ -> "ok" }).build()
        def tool2 = McpTool.builder().serverId("s").toolName("t")
            .inputSchema([:]).invoker({ _ -> "ok" }).build()

        when:
        def meta1 = McpToolAdapter.toToolMetadata(tool1)
        def meta2 = McpToolAdapter.toToolMetadata(tool2)

        then:
        meta1.inputSchema() instanceof JsonNode
        meta1.inputSchema().isEmpty()
        meta2.inputSchema() instanceof JsonNode
        meta2.inputSchema().isEmpty()
    }

    def "toToolMetadata 的 MethodHandle 应正确调用 McpTool.invoke()"() {
        given:
        def tool = McpTool.builder()
            .serverId("test").toolName("echo")
            .invoker({ Map p -> "invoked: ${p.msg}" as String })
            .build()
        def meta = McpToolAdapter.toToolMetadata(tool)

        when:
        def result = meta.invoke([msg: "hello"])

        then:
        result == "invoked: hello"
    }

    def "toToolMetadata 的 MethodHandle 可在 ToolRegistry 中注册并通过 ExecutionEngine 执行"() {
        given:
        def tool = McpTool.builder()
            .serverId("test").toolName("ping")
            .description("Ping tool")
            .inputSchema(["type": "object"])
            .invoker({ Map p -> "pong" })
            .build()

        def registry = new ToolRegistry()
        def meta = McpToolAdapter.toToolMetadata(tool)
        registry.register(meta)

        def engine = ExecutionEngine.builder().registry(registry).build()

        when:
        def result = engine.invoke("test:ping", [:])

        then:
        result.status() == org.cland.alice.tool.gateway.engine.ToolResult.Status.SUCCESS
        result.rawData() == "pong"
    }

    def "toToolMetadataArray 应批量转换 McpTool 列表"() {
        given:
        def invoker = { Map p -> "ok" } as McpTool.ToolInvoker
        def tools = [
            McpTool.builder().serverId("s1").toolName("t1").invoker(invoker).build(),
            McpTool.builder().serverId("s1").toolName("t2").invoker(invoker).build(),
            McpTool.builder().serverId("s2").toolName("t3").invoker(invoker).build(),
        ]

        when:
        def metas = McpToolAdapter.toToolMetadataArray(tools)

        then:
        metas.length == 3
        metas[0].name() == "s1:t1"
        metas[1].name() == "s1:t2"
        metas[2].name() == "s2:t3"
    }

    def "已注册的 MCP 工具应与 builtin 工具共存于同一 ToolRegistry"() {
        given:
        def registry = new ToolRegistry()

        // 注册一个 builtin 工具（模拟）
        def invoker = { Map p -> "builtin-ok" } as McpTool.ToolInvoker
        def mcpTool = McpTool.builder()
            .serverId("fs").toolName("read")
            .invoker(invoker).build()
        registry.register(McpToolAdapter.toToolMetadata(mcpTool))

        // 注册一个 MCP 工具
        registry.register(createBuiltinToolMeta("list_dir"))

        when:
        def mcpMeta = registry.lookup("fs:read")
        def builtinMeta = registry.lookup("list_dir")

        then:
        mcpMeta.name() == "fs:read"
        builtinMeta.name() == "list_dir"
    }

    // ========== Helpers ==========

    static ToolMetadata createBuiltinToolMeta(String name) {
        return ToolMetadata.builder()
            .name(name)
            .description("Builtin " + name)
            .inputSchema(MAPPER.createObjectNode())
            .targetMethod(
                java.lang.invoke.MethodHandles.lookup().findStatic(
                    McpToolAdapterSpec, "noopMethod",
                    java.lang.invoke.MethodType.methodType(String.class)))
            .riskLevel(RiskLevel.LOW)
            .returnType(String.class)
            .build()
    }

    static String noopMethod() { return "builtin" }
}
