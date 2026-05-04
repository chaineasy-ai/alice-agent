package org.cland.alice.tool.gateway.schema

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.cland.alice.tool.gateway.annotation.ToolParam
import spock.lang.Specification

class SchemaGeneratorSpec extends Specification {

    def mapper = new ObjectMapper()

    def "should generate schema for string parameter"() {
        given:
        def method = SchemaBean.getMethod("greet", String)

        when:
        def schema = SchemaGenerator.generateSchema(method)

        then:
        schema.path("type").asText() == "object"
        schema.path("properties").path("name").path("type").asText() == "string"
        schema.path("required").toString() == '["name"]'
    }

    def "should generate schema with ToolParam metadata"() {
        given:
        def method = SchemaBean.getMethod("readFile", String, String)

        when:
        def schema = SchemaGenerator.generateSchema(method)

        then:
        schema.path("properties").path("path").path("type").asText() == "string"
        schema.path("properties").path("path").path("description").asText() == "File path to read"
        schema.path("properties").path("encoding").path("type").asText() == "string"
        schema.path("properties").path("encoding").path("description").asText() == "File encoding"
        // path is required, encoding is not
        schema.path("required").toString() == '["path"]'
    }

    def "should generate schema for integer parameter"() {
        given:
        def method = SchemaBean.getMethod("compute", Integer)

        when:
        def schema = SchemaGenerator.generateSchema(method)

        then:
        schema.path("properties").path("count").path("type").asText() == "integer"
    }

    def "should generate schema for double parameter"() {
        given:
        def method = SchemaBean.getMethod("calc", double)

        when:
        def schema = SchemaGenerator.generateSchema(method)

        then:
        schema.path("properties").path("value").path("type").asText() == "number"
    }

    def "should generate schema for boolean parameter"() {
        given:
        def method = SchemaBean.getMethod("toggle", Boolean)

        when:
        def schema = SchemaGenerator.generateSchema(method)

        then:
        schema.path("properties").path("flag").path("type").asText() == "boolean"
    }

    def "should generate schema for list parameter"() {
        given:
        def method = SchemaBean.getMethod("processList", List)

        when:
        def schema = SchemaGenerator.generateSchema(method)

        then:
        schema.path("properties").path("items").path("type").asText() == "array"
    }

    def "should generate schema for enum parameter"() {
        given:
        def method = SchemaBean.getMethod("setMode", Mode)

        when:
        def schema = SchemaGenerator.generateSchema(method)

        then:
        schema.path("properties").path("mode").path("type").asText() == "string"
        schema.path("properties").path("mode").path("enum").isArray()
        schema.path("properties").path("mode").path("enum").toString().contains("FAST")
        schema.path("properties").path("mode").path("enum").toString().contains("SLOW")
    }

    def "should generate schema for Optional parameter (extract inner type)"() {
        given:
        def method = SchemaBean.getMethod("withOptional", Optional)

        when:
        def schema = SchemaGenerator.generateSchema(method)

        then:
        schema.path("properties").path("name").path("type").asText() == "string"
        // Optional<T> should not be in required
    }

    def "should generate empty schema for no-arg method"() {
        given:
        def method = SchemaBean.getMethod("noop")

        when:
        def schema = SchemaGenerator.generateSchema(method)

        then:
        schema.path("type").asText() == "object"
        schema.path("properties").isObject()
        schema.path("required").isArray()
        schema.path("required").size() == 0
    }

    def "should generate simple schema for Map parameter"() {
        given:
        def method = SchemaBean.getMethod("handleMap", Map)

        when:
        def schema = SchemaGenerator.generateSchema(method)

        then:
        schema.path("type").asText() == "object"
        schema.path("additionalProperties").asBoolean()
    }

    def "should handle primitive int parameter"() {
        given:
        def method = SchemaBean.getMethod("primitiveInt", int)

        when:
        def schema = SchemaGenerator.generateSchema(method)

        then:
        schema.path("properties").path("x").path("type").asText() == "integer"
    }

    // ========== Test fixtures ==========

    enum Mode { FAST, SLOW, NORMAL }

    static class SchemaBean {
        String greet(@ToolParam("name") String name) { name }

        String readFile(
            @ToolParam(value = "path", description = "File path to read") String path,
            @ToolParam(value = "encoding", description = "File encoding", required = false) String encoding
        ) { "" }

        int compute(@ToolParam("count") Integer count) { count }
        double calc(@ToolParam("value") double value) { value }
        boolean toggle(@ToolParam("flag") Boolean flag) { flag }
        List<String> processList(@ToolParam("items") List<String> items) { items }
        String setMode(@ToolParam("mode") Mode mode) { mode.name() }
        String withOptional(@ToolParam("name") Optional<String> name) { name.orElse("default") }
        void noop() {}
        void handleMap(@ToolParam("params") Map<String, Object> params) {}
        int primitiveInt(@ToolParam("x") int x) { x }
    }
}
