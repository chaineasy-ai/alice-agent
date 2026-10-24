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
        def plan = Plan.fastPath("read temp file", Plan.Intent.SEARCH, "/tmp/data.txt")

        when:
        def result = validator.check(plan)

        then:
        result.isPassed()
        result.status() == AuditResult.Status.ALLOW
    }

    def "GRD-P05: check returns REJECT for /etc/ target"() {
        given:
        def plan = Plan.fastPath("read config", Plan.Intent.SEARCH, "/etc/shadow")

        when:
        def result = validator.check(plan)

        then:
        !result.isPassed()
        result.status() == AuditResult.Status.REJECT
    }

    def "GRD-P05: check returns REJECT for rm -rf / command"() {
        given:
        def plan = Plan.fastPath("wipe system", Plan.Intent.SEARCH, "rm -rf /")

        when:
        def result = validator.check(plan)

        then:
        !result.isPassed()
        result.status() == AuditResult.Status.REJECT
    }

    def "GRD-P05: check returns REJECT for /proc/ target"() {
        given:
        def plan = Plan.fastPath("read proc", Plan.Intent.SEARCH, "/proc/self/mem")

        when:
        def result = validator.check(plan)

        then:
        !result.isPassed()
        result.status() == AuditResult.Status.REJECT
    }

    def "GRD-P05: check returns ALLOW for custom added forbidden prefix"() {
        given:
        validator.addForbiddenPrefix("/home/secret/")
        def blockedPlan = Plan.fastPath("access secret", Plan.Intent.SEARCH, "/home/secret/keys.txt")
        def safePlan = Plan.fastPath("access public", Plan.Intent.SEARCH, "/home/public/readme.txt")

        expect:
        !validator.check(blockedPlan).isPassed()
        validator.check(safePlan).isPassed()
    }

    // ── #665: 参数层扫描（装配层真实形态 target=工具名） ──────────────

    def "GRD-P05-a: REJECT when read_file params carry a restricted path (/etc/passwd)"() {
        given: "GuardrailToolProxy 装配出的真实形态：target 是工具名，路径在 params"
        def plan = Plan.builder()
                .type(Plan.Type.FAST_PATH)
                .summary("GuardrailToolProxy: read_file")
                .addStep(Plan.Step.of(Plan.Intent.SEARCH, "read_file", [path: "/etc/passwd"]))
                .build()

        when:
        def result = validator.check(plan)

        then:
        !result.isPassed()
        result.status() == AuditResult.Status.REJECT
        result.reason().contains("restricted path")
        result.reason().contains("/etc/")
    }

    def "GRD-P05-b: REJECT when run params carry a destructive command (dd)"() {
        given: "命令在 params.command 里，target 只是工具名 run"
        def plan = Plan.builder()
                .type(Plan.Type.FAST_PATH)
                .summary("GuardrailToolProxy: run")
                .addStep(Plan.Step.of(Plan.Intent.SEARCH, "run",
                        [command: "dd if=/dev/zero of=/tmp/x bs=1 count=1"]))
                .build()

        when:
        def result = validator.check(plan)

        then:
        !result.isPassed()
        result.status() == AuditResult.Status.REJECT
        result.reason().contains("Permission denied")
    }

    def "GRD-P05-c: REJECT when restricted command is nested inside params list"() {
        given: "嵌套 List 参数：args=[sh, -c, mkfs.ext4 /dev/sdb]"
        def plan = Plan.builder()
                .type(Plan.Type.FAST_PATH)
                .summary("GuardrailToolProxy: run")
                .addStep(Plan.Step.of(Plan.Intent.SEARCH, "run",
                        [args: ["sh", "-c", "mkfs.ext4 /dev/sdb"]]))
                .build()

        when:
        def result = validator.check(plan)

        then:
        !result.isPassed()
        result.status() == AuditResult.Status.REJECT
        result.reason().contains("Permission denied")
    }

    def "GRD-P05-d: ALLOW for benign params (no false positive)"() {
        given: "正控：正常读临时文件 / 正常列目录不能被误杀"
        def readPlan = Plan.builder()
                .type(Plan.Type.FAST_PATH)
                .summary("GuardrailToolProxy: read_file")
                .addStep(Plan.Step.of(Plan.Intent.SEARCH, "read_file", [path: "/tmp/data.txt"]))
                .build()
        def runPlan = Plan.builder()
                .type(Plan.Type.FAST_PATH)
                .summary("GuardrailToolProxy: run")
                .addStep(Plan.Step.of(Plan.Intent.SEARCH, "run", [command: "ls -l /tmp"]))
                .build()

        expect:
        validator.check(readPlan).isPassed()
        validator.check(readPlan).status() == AuditResult.Status.ALLOW
        validator.check(runPlan).isPassed()
        validator.check(runPlan).status() == AuditResult.Status.ALLOW
    }
}
