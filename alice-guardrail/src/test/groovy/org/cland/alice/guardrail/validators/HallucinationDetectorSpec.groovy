package org.cland.alice.guardrail.validators

import org.cland.alice.core.planner.Plan
import org.cland.alice.guardrail.AuditResult
import spock.lang.Specification
import spock.lang.Title

/**
 * Spock spec for HallucinationDetector — covering GRD-P04.
 *
 * Hole test (洞测试):
 *   GRD-P04: HallucinationDetector detects contradictions/empty results
 */
@Title("HallucinationDetector — empty result and error pattern detection")
class HallucinationDetectorSpec extends Specification {

    def detector = new HallucinationDetector()
    def plan = Plan.fastPath("search", Plan.Intent.SEARCH, "web")

    // ── GRD-P04: HallucinationDetector ───────────────────────────────

    def "GRD-P04: check returns ALLOW for normal observation with valid data"() {
        given:
        def observation = [
                "status" : "SUCCESS",
                "summary": "Found relevant data",
                "rawData": '{"results": [{"id": 1, "title": "Alice"}]}'
        ] as Map<String, Object>

        when:
        def result = detector.check(observation, plan)

        then:
        result.isPassed()
        result.status() == AuditResult.Status.ALLOW
    }

    def "GRD-P04: check returns INVALID when rawData contains 'no results found'"() {
        given:
        def observation = [
                "status" : "SUCCESS",
                "summary": "Search completed",
                "rawData": "no results found for query"
        ] as Map<String, Object>

        when:
        def result = detector.check(observation, plan)

        then:
        !result.isPassed()
        result.status() == AuditResult.Status.INVALID
    }

    def "GRD-P04: check returns INVALID when rawData contains error pattern"() {
        given:
        def observation = [
                "status" : "SUCCESS",
                "summary": "Execution completed",
                "rawData": "Error: connection refused to remote host"
        ] as Map<String, Object>

        when:
        def result = detector.check(observation, plan)

        then:
        !result.isPassed()
        result.status() == AuditResult.Status.INVALID
    }

    def "GRD-P04: check returns ALLOW when rawData is source containing 'timeout' identifier (#521)"() {
        given:
        // 读源码时很常见的合法内容：`timeout=` / `Timeout in milliseconds` / `TIMEOUT_MS`
        def observation = [
                "status" : "SUCCESS",
                "summary": "read_file config.py",
                "rawData": "# Timeout in milliseconds (legacy)\nTIMEOUT_MS = 5000\nprint(f\"timeout={TIMEOUT_MS}ms\")"
        ] as Map<String, Object>

        when:
        def result = detector.check(observation, plan)

        then:
        result.isPassed()
        result.status() == AuditResult.Status.ALLOW
    }

    def "GRD-P04: check returns INVALID when rawData contains a real timeout error (#521)"() {
        given:
        def observation = [
                "status" : "SUCCESS",
                "summary": "tool output",
                "rawData": "request timed out after 30s"
        ] as Map<String, Object>

        when:
        def result = detector.check(observation, plan)

        then:
        !result.isPassed()
        result.status() == AuditResult.Status.INVALID
    }

    def "GRD-P04: check returns INVALID for FAILURE status observation"() {
        given:
        def observation = [
                "status" : "FAILURE",
                "summary": "Tool crashed with exception",
                "rawData": ""
        ] as Map<String, Object>

        when:
        def result = detector.check(observation, plan)

        then:
        !result.isPassed()
        result.status() == AuditResult.Status.INVALID
    }

    def "GRD-P04: check returns ALLOW for empty rawData with FINISH plan (no type check)"() {
        given:
        def finishPlan = Plan.fastPath("done", Plan.Intent.FINISH, "completed")
        def observation = [
                "status" : "SUCCESS",
                "summary": "Task completed",
                "rawData": ""
        ] as Map<String, Object>

        when:
        def result = detector.check(observation, finishPlan)

        then:
        result.isPassed()
        result.status() == AuditResult.Status.ALLOW
    }

    def "GRD-P04: check returns INVALID for empty rawData with TOOL_CALL plan (type consistency)"() {
        given:
        def observation = [
                "status" : "SUCCESS",
                "summary": "No data returned but no error",
                "rawData": ""
        ] as Map<String, Object>

        when:
        def result = detector.check(observation, plan)

        then:
        !result.isPassed()
        result.status() == AuditResult.Status.INVALID
    }
}
