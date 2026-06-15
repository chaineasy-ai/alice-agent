package org.cland.alice.model.supplier

import org.cland.alice.model.Call
import org.cland.alice.model.Model
import org.cland.alice.model.ModelProvider
import spock.lang.Specification
import spock.lang.Title

/**
 * 测试 {@link ClaudeSupplier} — Anthropic Messages API 适配器。
 */
@Title("ClaudeSupplier 规格")
class ClaudeSupplierSpec extends Specification {

    static final String MOCK_API_KEY = "sk-ant-test-12345"
    static final String MODEL_ID = "claude-3-5-sonnet-latest"

    def cleanup() {
        ModelProvider.reset()
    }

    def "name 应返回供应商名称"() {
        expect:
        new ClaudeSupplier(MOCK_API_KEY).name() == "anthropic"
    }

    def "自定义名称应生效"() {
        expect:
        new ClaudeSupplier("my-anthropic", MOCK_API_KEY, "http://localhost/v1/messages").name() == "my-anthropic"
    }

    def "constructor 应拒绝 null apiKey"() {
        when:
        new ClaudeSupplier(null)
        then:
        thrown(NullPointerException)
    }

    def "constructor 应拒绝 null apiKey (三参)"() {
        when:
        new ClaudeSupplier("test", null, "http://localhost")
        then:
        thrown(NullPointerException)
    }

    def "request 对不可达端点应抛出异常"() {
        given:
        def supplier = new ClaudeSupplier(MOCK_API_KEY)
        def call = Call.builder()
            .payload(new Call.Payload(MODEL_ID, "test", [:]))
            .build()

        when:
        supplier.request(call)

        then:
        thrown(Exception)
    }

    def "通过 ModelProvider 调度失败时应包装异常"() {
        given:
        def provider = ModelProvider.getInstance()
        provider.registerModel(Model.builder()
            .modelId(MODEL_ID)
            .supplierName("anthropic")
            .build())
        provider.registerSupplier(new ClaudeSupplier(MOCK_API_KEY))

        when:
        provider.dispatch(MODEL_ID, "Hello!")

        then:
        def e = thrown(RuntimeException)
        e.message.contains("Model call failed")
    }

    // ========================================================================
    // 私有方法测试（Groovy 可以直接访问 private 方法）
    // ========================================================================

    def "parseResponse 应提取 Anthropic 响应文本"() {
        given:
        def supplier = new ClaudeSupplier(MOCK_API_KEY)
        def json = '''{"id":"msg_1","type":"message","role":"assistant","content":[{"type":"text","text":"Sure, I can help!"}],"usage":{"input_tokens":12,"output_tokens":5}}'''

        when:
        def response = supplier.parseResponse(json, MODEL_ID)

        then:
        response.content() == "Sure, I can help!"
        response.tokenUsage() != null
        response.tokenUsage().promptTokens() == 12
        response.tokenUsage().completionTokens() == 5
    }

    def "parseResponse 对无效 JSON 应回退到原始 body"() {
        given:
        def supplier = new ClaudeSupplier(MOCK_API_KEY)

        when:
        def response = supplier.parseResponse("not json", MODEL_ID)

        then:
        response.content() == "not json"
        response.tokenUsage() == null
    }

    def "parseResponse 应检测 tool_use stop_reason"() {
        given:
        def supplier = new ClaudeSupplier(MOCK_API_KEY)
        def json = '''{"stop_reason":"tool_use","content":[{"type":"text","text":"Let me check"}],"usage":{"input_tokens":5,"output_tokens":10}}'''

        when:
        def response = supplier.parseResponse(json, MODEL_ID)

        then:
        response.metadata()["stop_reason"] == "tool_use"
        response.metadata()["supports_tools"] == true
    }

    def "extractAnthropicTextContent 应提取 text 块内容"() {
        given:
        def supplier = new ClaudeSupplier(MOCK_API_KEY)

        expect:
        supplier.extractAnthropicTextContent('{"content":[{"type":"text","text":"Hello!"}]}') == "Hello!"
    }

    def "extractAnthropicTextContent 对空数组应返回 null"() {
        given:
        def supplier = new ClaudeSupplier(MOCK_API_KEY)

        expect:
        supplier.extractAnthropicTextContent('{"content":[]}') == null
    }

    def "extractAnthropicTextContent 对 null 应返回 null"() {
        given:
        def supplier = new ClaudeSupplier(MOCK_API_KEY)

        expect:
        supplier.extractAnthropicTextContent(null) == null
    }

    def "extractAnthropicTextContent 对无 content 字段应返回 null"() {
        given:
        def supplier = new ClaudeSupplier(MOCK_API_KEY)

        expect:
        supplier.extractAnthropicTextContent('{"id":"msg_1"}') == null
    }
}
