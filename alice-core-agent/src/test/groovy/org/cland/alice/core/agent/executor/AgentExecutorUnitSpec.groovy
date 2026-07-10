package org.cland.alice.core.agent.executor

import spock.lang.Specification
import spock.lang.Timeout
import java.lang.reflect.Method

/**
 * Unit tests for private helper methods in AgentExecutor.
 *
 * These tests use Java reflection to access package-private/private static methods
 * since Groovy cannot directly call Java private methods.
 *
 * Coverage:
 * - countToolCallMarkers(): text marker detection
 * - parseToolArgsJson(): structured Function Calling argument parsing
 * - __action_log accumulation/truncation via AgentContext
 */
@Timeout(5)
class AgentExecutorUnitSpec extends Specification {

    // ========================================================================
    // Reflection helpers
    // ========================================================================

    private static Method findMethod(String name, Class<?>... paramTypes) {
        // 静态辅助方法已从 AgentExecutor 移至 MicroReActEngine (SRP 拆分)
        def m = MicroReActEngine.getDeclaredMethod(name, paramTypes)
        m.setAccessible(true)
        return m
    }

    private static Object invokeStatic(String name, Object... args) {
        def m = findMethod(name, args*.getClass() as Class<?>[])
        return m.invoke(null, args)
    }

    // Helper: try arg type coercion for methods that take Object parameters
    private static Object invokeStaticTyped(String name, Class<?>[] paramTypes, Object... args) {
        def m = findMethod(name, paramTypes)
        return m.invoke(null, args)
    }

    private static Object invokeNullArg(String name, Class<?> paramType) {
        def m = findMethod(name, paramType)
        // Invoke with null args array to pass null as the actual parameter
        return m.invoke(null, (Object[]) [null])
    }

    // ========================================================================
    // parseToolArgsJson
    // ========================================================================

    def "parseToolArgsJson should parse simple flat JSON"() {
        when:
        def result = invokeStaticTyped("parseToolArgsJson",
                [String] as Class<?>[], '{"path": "test.py", "content": "hello"}')

        then:
        result instanceof Map
        result["path"] == "test.py"
        result["content"] == "hello"
    }

    def "parseToolArgsJson should handle escaped newlines"() {
        when:
        def result = invokeStaticTyped("parseToolArgsJson",
                [String] as Class<?>[],
                '{"path": "test.py", "content": "def foo():\\n    return 1\\n"}')

        then:
        result instanceof Map
        result["path"] == "test.py"
        result["content"] == "def foo():\n    return 1\n"
    }

    def "parseToolArgsJson should handle escaped tabs"() {
        when:
        def result = invokeStaticTyped("parseToolArgsJson",
                [String] as Class<?>[],
                '{"content": "if true:\\n\\treturn 1"}')

        then:
        result instanceof Map
        result["content"] == "if true:\n\treturn 1"
    }

    def "parseToolArgsJson should handle double quotes inside content"() {
        when:
        def result = invokeStaticTyped("parseToolArgsJson",
                [String] as Class<?>[],
                '{"path": "test.py", "content": "print(\\"hello\\")"}')

        then:
        result instanceof Map
        result["path"] == "test.py"
        result["content"] == 'print("hello")'
    }

    def "parseToolArgsJson should handle empty braces"() {
        when:
        def result = invokeStaticTyped("parseToolArgsJson",
                [String] as Class<?>[], "{}")

        then:
        result instanceof Map
        result.isEmpty()
    }

    def "parseToolArgsJson should handle single key"() {
        when:
        def result = invokeStaticTyped("parseToolArgsJson",
                [String] as Class<?>[], '{"key": "value"}')

        then:
        result instanceof Map
        result["key"] == "value"
    }

    def "parseToolArgsJson should return empty map on malformed JSON"() {
        when:
        def result = invokeStaticTyped("parseToolArgsJson",
                [String] as Class<?>[], "not json at all")

        then:
        result instanceof Map
        result.isEmpty()
    }

    def "parseToolArgsJson should return empty map on null input"() {
        when:
        def result = invokeNullArg("parseToolArgsJson", String)

        then:
        result instanceof Map
        result.isEmpty()
    }

    def "parseToolArgsJson should return empty map on empty string"() {
        when:
        def result = invokeStaticTyped("parseToolArgsJson",
                [String] as Class<?>[], "")

        then:
        result instanceof Map
        result.isEmpty()
    }

    // ========================================================================
    // parseToolArgsJson: content with Python code
    // ========================================================================

    def "parseToolArgsJson should parse realistic Python file content"() {
        given:
        // Build content as Groovy multi-line string, then JSON-encode it via Jackson
        def content = '''"""Simple math utilities."""

def divide(a: float, b: float) -> float:
    """Divide a by b. Raises ValueError if b is zero."""
    if b == 0:
        raise ValueError('Division by zero is not allowed')
    return a / b
'''
        // Use Jackson to build the JSON string with proper escaping
        def mapper = new com.fasterxml.jackson.databind.ObjectMapper()
        def root = mapper.createObjectNode()
        root.put("path", "math_utils.py")
        root.put("content", content)
        def json = mapper.writeValueAsString(root)

        when:
        def result = invokeStaticTyped("parseToolArgsJson",
                [String] as Class<?>[], json)

        then:
        result instanceof Map
        result["path"] == "math_utils.py"
        def parsedContent = result["content"] as String
        parsedContent.contains('"""Simple math utilities."""')
        parsedContent.contains("def divide(a: float, b: float) -> float:")
        parsedContent.contains("if b == 0:")
        parsedContent.contains("raise ValueError('Division by zero is not allowed')")
        parsedContent.contains("return a / b")
    }

    // ========================================================================
    // __action_log accumulation and truncation (via AgentContext)
    // ========================================================================

    def "agent context should store and retrieve __action_log"() {
        given:
        def ctx = new org.cland.alice.core.agent.AgentContext(10)

        when:
        ctx.put("__action_log", "Tool read_file returned:\nfile content\n\n")

        then:
        ctx.get("__action_log") == "Tool read_file returned:\nfile content\n\n"
    }

    def "agent context __action_log should support append pattern"() {
        given:
        def ctx = new org.cland.alice.core.agent.AgentContext(10)

        when:
        def log = ""
        if (ctx.containsKey("__action_log")) {
            log = ctx.get("__action_log").toString()
        }
        log += "Tool read_file returned:\nline1\n\n"
        ctx.put("__action_log", log)
        log = ctx.get("__action_log").toString()
        log += "Tool write_file succeeded.\n\n"
        ctx.put("__action_log", log)

        then:
        def finalLog = ctx.get("__action_log").toString()
        finalLog.contains("read_file")
        finalLog.contains("write_file")
        finalLog.contains("line1")
    }

    def "agent context __action_log should be empty initially"() {
        given:
        def ctx = new org.cland.alice.core.agent.AgentContext(10)

        expect:
        !ctx.containsKey("__action_log")
    }
}
