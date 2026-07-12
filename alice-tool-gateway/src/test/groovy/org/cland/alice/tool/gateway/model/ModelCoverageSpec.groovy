package org.cland.alice.tool.gateway.model

import org.cland.alice.tool.gateway.ToolRegistryHolder
import spock.lang.Specification

/**
 * 模型类覆盖率补充 — Tool/Resource/ResourceResult/ToolResult 基础构造测试
 */
class ModelCoverageSpec extends Specification {

    // ====================================================================
    // Tool
    // ====================================================================

    def "Tool builder should create tool with all fields"() {
        when:
        def t = Tool.builder()
            .name("search_web")
            .description("Search the web")
            .inputSchema([q: [type: "string"]])
            .build()

        then:
        t.name() == "search_web"
        t.description() == "Search the web"
        t.inputSchema() == [q: [type: "string"]]
    }

    def "Tool builder null schema defaults to empty"() {
        when:
        def t = Tool.builder().name("test").build()

        then:
        t.inputSchema() == [:]
    }

    def "Tool builder null name throws"() {
        when: Tool.builder().build()
        then: thrown(NullPointerException)
    }

    // ====================================================================
    // Resource
    // ====================================================================

    def "Resource builder with all fields"() {
        when:
        def r = Resource.builder()
            .uri("file:///test.txt")
            .mimeType("text/plain")
            .name("test file")
            .description("A test file")
            .build()

        then:
        r.uri() == "file:///test.txt"
        r.mimeType() == "text/plain"
        r.name() == "test file"
        r.description() == "A test file"
    }

    def "Resource builder minimal fields"() {
        when:
        def r = Resource.builder().uri("file:///test.txt").build()

        then:
        r.uri() == "file:///test.txt"
        r.mimeType() == null
        r.name() == null
        r.description() == null
    }

    def "Resource builder null uri throws"() {
        when: Resource.builder().build()
        then: thrown(NullPointerException)
    }

    // ====================================================================
    // ResourceResult
    // ====================================================================

    def "ResourceResult builder with all fields"() {
        when:
        def r = ResourceResult.builder()
            .uri("file:///test.txt")
            .mimeType("text/plain")
            .text("file content")
            .data([key: "value"])
            .sizeBytes(12)
            .build()

        then:
        r.uri() == "file:///test.txt"
        r.mimeType() == "text/plain"
        r.text() == "file content"
        r.data() == [key: "value"]
        r.sizeBytes() == 12
    }

    def "ResourceResult builder null data defaults to empty"() {
        when:
        def r = ResourceResult.builder()
            .uri("u")
            .mimeType("t")
            .build()

        then:
        r.data() == [:]
        r.sizeBytes() == 0
    }

    def "ResourceResult builder null uri throws"() {
        when: ResourceResult.builder().mimeType("t").build()
        then: thrown(NullPointerException)
    }

    def "ResourceResult builder null mimeType throws"() {
        when: ResourceResult.builder().uri("u").build()
        then: thrown(NullPointerException)
    }

    // ====================================================================
    // ToolResult (tool-gateway.model, not core-agent or env-adapter)
    // ====================================================================

    def "ToolResult builder should create success result"() {
        when:
        def r = ToolResult.builder()
            .status(ToolResult.Status.SUCCESS)
            .text("result text")
            .content([key: "val"])
            .build()

        then:
        r.isSuccess()
        !r.isError()
        r.text() == "result text"
        r.content() == [key: "val"]
    }

    def "ToolResult builder error result"() {
        when:
        def r = ToolResult.builder()
            .status(ToolResult.Status.ERROR)
            .text("error occurred")
            .isError(true)
            .build()

        then:
        !r.isSuccess()
        r.isError()
    }

    def "ToolResult builder minimal"() {
        when:
        def r = ToolResult.builder()
            .status(ToolResult.Status.SUCCESS)
            .build()

        then:
        r.isSuccess()
    }

    def "ToolResult static factories"() {
        expect:
        ToolResult.success().isSuccess()
        ToolResult.success("done").text() == "done"
        ToolResult.error("fail").isError()
    }

    // ====================================================================
    // ToolRegistryHolder
    // ====================================================================

    def "ToolRegistryHolder singleton"() {
        expect:
        ToolRegistryHolder.INSTANCE.is(ToolRegistryHolder.INSTANCE)
        ToolRegistryHolder.INSTANCE.registry() != null
    }
}
