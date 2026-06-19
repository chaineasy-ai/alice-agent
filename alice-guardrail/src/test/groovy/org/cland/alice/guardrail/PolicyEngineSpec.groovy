package org.cland.alice.guardrail

import spock.lang.Specification
import spock.lang.Title

/**
 * Spock spec for PolicyEngine — covering GRD-P03.
 *
 * Hole test (洞测试):
 *   GRD-P03: PolicyEngine — JsonSchemaValidator + RegexSafetyFilter
 */
@Title("PolicyEngine — Schema validation and Regex safety filter")
class PolicyEngineSpec extends Specification {

    // ── GRD-P03: PolicyEngine ────────────────────────────────────────

    def "GRD-P03: JsonSchemaValidator.validate() returns true for balanced JSON"() {
        given:
        def engine = new PolicyEngine()
        engine.schemaValidator().registerSchema("test", """{"type": "object"}""")
        def validJson = '{"name": "alice", "value": 42}'

        expect:
        engine.schemaValidator().validate("test", validJson)
    }

    def "GRD-P03: JsonSchemaValidator.validate() returns false for unbalanced JSON"() {
        given:
        def engine = new PolicyEngine()
        engine.schemaValidator().registerSchema("test", """{"type": "object"}""")
        def invalidJson = '{"name": "alice", "value": 42'  // missing closing brace

        expect:
        !engine.schemaValidator().validate("test", invalidJson)
    }

    def "GRD-P03: JsonSchemaValidator.validate() returns false for unregistered schema"() {
        given:
        def engine = new PolicyEngine()

        expect:
        !engine.schemaValidator().validate("nonexistent", "{}")
    }

    def "GRD-P03: JsonSchemaValidator.validate() structured data type checking"() {
        given:
        def engine = new PolicyEngine()
        def rules = [
                "name" : String,
                "count": Integer
        ] as Map<String, Class<?>>
        def validData = ["name": "alice", "count": 42] as Map<String, Object>

        when:
        def violations = engine.schemaValidator().validate(rules, validData)

        then:
        violations.isEmpty()
    }

    def "GRD-P03: JsonSchemaValidator.validate() returns violations for type mismatch"() {
        given:
        def engine = new PolicyEngine()
        def rules = [
                "name" : String,
                "count": Integer
        ] as Map<String, Class<?>>
        def invalidData = ["name": 123, "count": "not a number"] as Map<String, Object>

        when:
        def violations = engine.schemaValidator().validate(rules, invalidData)

        then:
        violations.size() == 2
        violations.any { it.contains("name") && it.contains("String") }
        violations.any { it.contains("count") && it.contains("Integer") }
    }

    def "GRD-P03: RegexSafetyFilter.isSafe() allows safe content"() {
        given:
        def engine = new PolicyEngine()
        engine.safetyFilter().addDenyPattern(".*DROP.*")

        expect:
        engine.safetyFilter().isSafe("SELECT * FROM users")
    }

    def "GRD-P03: RegexSafetyFilter.isSafe() blocks content matching deny pattern"() {
        given:
        def engine = new PolicyEngine()
        engine.safetyFilter().addDenyPattern(".*DROP.*")

        expect:
        !engine.safetyFilter().isSafe("DROP TABLE users")
    }

    def "GRD-P03: RegexSafetyFilter.isSafe() requires allow pattern match when present"() {
        given:
        def engine = new PolicyEngine()
        engine.safetyFilter().addDenyPattern(".*DROP.*")
        engine.safetyFilter().addAllowPattern("SELECT.*")

        expect:
        engine.safetyFilter().isSafe("SELECT * FROM users")
        !engine.safetyFilter().isSafe("INSERT INTO users")  // no allow pattern match
    }

    def "GRD-P03: RegexSafetyFilter.firstViolation() returns violation description"() {
        given:
        def engine = new PolicyEngine()
        engine.safetyFilter().addDenyPattern(".*DROP.*")

        when:
        def violation = engine.safetyFilter().firstViolation("DROP TABLE users")

        then:
        violation.isPresent()
        violation.get().contains("DROP")
    }
}
