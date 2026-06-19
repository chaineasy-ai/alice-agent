package org.cland.alice.guardrail.validators

import org.cland.alice.core.planner.Plan
import org.cland.alice.guardrail.AuditResult
import spock.lang.Specification
import spock.lang.Title

/**
 * Spock spec for PermissionSandboxValidator — covering GRD-P05.
 *
 * Hole test (洞测试):
 *   GRD-P05: PermissionSandboxValidator — bounded vs out-of-bounds access
 */
@Title("PermissionSandboxValidator — access control on plan targets")
class PermissionSandboxValidatorSpec extends Specification {

    def validator = new PermissionSandboxValidator()

    // ── GRD-P05: PermissionSandboxValidator ──────────────────────────

    def "GRD-P05: check returns ALLOW for a safe, bounded target"() {
        given:
        def plan = Plan.fastPath("read temp file", "TOOL_CALL", "/tmp/data.txt")

        when:
        def result = validator.check(plan)

        then:
        result.isPassed()
        result.status() == AuditResult.Status.ALLOW
    }

    def "GRD-P05: check returns REJECT for /etc/ target"() {
        given:
        def plan = Plan.fastPath("read config", "TOOL_CALL", "/etc/shadow")

        when:
        def result = validator.check(plan)

        then:
        !result.isPassed()
        result.status() == AuditResult.Status.REJECT
    }

    def "GRD-P05: check returns REJECT for rm -rf / command"() {
        given:
        def plan = Plan.fastPath("wipe system", "TOOL_CALL", "rm -rf /")

        when:
        def result = validator.check(plan)

        then:
        !result.isPassed()
        result.status() == AuditResult.Status.REJECT
    }

    def "GRD-P05: check returns REJECT for /proc/ target"() {
        given:
        def plan = Plan.fastPath("read proc", "TOOL_CALL", "/proc/self/mem")

        when:
        def result = validator.check(plan)

        then:
        !result.isPassed()
        result.status() == AuditResult.Status.REJECT
    }

    def "GRD-P05: check returns ALLOW for custom added forbidden prefix"() {
        given:
        validator.addForbiddenPrefix("/home/secret/")
        def blockedPlan = Plan.fastPath("access secret", "TOOL_CALL", "/home/secret/keys.txt")
        def safePlan = Plan.fastPath("access public", "TOOL_CALL", "/home/public/readme.txt")

        expect:
        !validator.check(blockedPlan).isPassed()
        validator.check(safePlan).isPassed()
    }
}
