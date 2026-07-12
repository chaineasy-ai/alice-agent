package org.cland.alice.guardrail.validators

import org.cland.alice.core.planner.Plan
import org.cland.alice.guardrail.AuditResult
import org.cland.alice.guardrail.CorrectionSuggestion
import org.cland.alice.guardrail.Verificator
import org.cland.alice.tool.gateway.ToolRegistry

import java.util.Arrays
import java.util.HashSet
import java.util.Set

import spock.lang.Specification

/**
 * 覆盖率补充测试 — 覆盖 ToolMicroLoopValidator、ToolResultValidator、
 * LogicSanityValidator、ToolExistenceValidator、AuditResult、CorrectionSuggestion、Verificator
 */
class ValidatorCoverageSpec extends Specification {

    // ====================================================================
    // ToolRegistry 工厂 — 使用简单实现避开 Spock mock 限制
    // ====================================================================

    static class MemToolRegistry extends ToolRegistry {
        final Set<String> toolNames = [] as Set
        MemToolRegistry(Set<String> names) { this.toolNames.addAll(names) }
        @Override boolean hasTool(String name) { return toolNames.contains(name) }
        @Override Set<String> toolNames() { return toolNames }
    }

    private ToolRegistry registryWith(String... toolNames) {
        return new MemToolRegistry(new HashSet<>(Arrays.asList(toolNames)))
    }

    // ====================================================================
    // ToolExistenceValidator (0% → 需要基本分支覆盖)
    // ====================================================================

    def "ToolExistenceValidator null registry throws"() {
        when: new ToolExistenceValidator(null)
        then: thrown(NullPointerException)
    }

    def "ToolExistenceValidator empty plan returns ALLOW"() {
        given:
        def v = new ToolExistenceValidator(registryWith("tool1"))
        def plan = Plan.builder().type(Plan.Type.FAST_PATH).summary("empty").build()

        expect:
        v.check(plan).isPassed()
    }

    def "ToolExistenceValidator non-TOOL_CALL steps are skipped"() {
        given:
        def v = new ToolExistenceValidator(registryWith("tool1"))
        def plan = Plan.fastPath("test", "LLM_INFERENCE", "gpt-4o")

        expect:
        v.check(plan).isPassed()
    }

    def "ToolExistenceValidator null toolName returns REJECT"() {
        given:
        def v = new ToolExistenceValidator(registryWith("tool1"))
        def plan = Plan.builder()
            .type(Plan.Type.FAST_PATH).summary("null")
            .addStep(Plan.Step.of("TOOL_CALL", null))
            .build()

        expect:
        !v.check(plan).isPassed()
    }

    def "ToolExistenceValidator known tool returns ALLOW"() {
        given:
        def v = new ToolExistenceValidator(registryWith("search_web"))
        def plan = Plan.builder()
            .type(Plan.Type.FAST_PATH).summary("search")
            .addStep(Plan.Step.of("TOOL_CALL", "search_web"))
            .build()

        expect:
        v.check(plan).isPassed()
    }

    def "ToolExistenceValidator unknown tool returns REJECT"() {
        given:
        def v = new ToolExistenceValidator(registryWith("search_web"))
        def plan = Plan.builder()
            .type(Plan.Type.FAST_PATH).summary("bad")
            .addStep(Plan.Step.of("TOOL_CALL", "nonexistent_tool"))
            .build()

        expect:
        !v.check(plan).isPassed()
    }

    def "ToolExistenceValidator empty registry returns REJECT with message"() {
        given:
        def v = new ToolExistenceValidator(registryWith())
        def plan = Plan.builder()
            .type(Plan.Type.FAST_PATH).summary("empty")
            .addStep(Plan.Step.of("TOOL_CALL", "any"))
            .build()

        expect:
        !v.check(plan).isPassed()
    }

    // ====================================================================
    // LogicSanityValidator (0%)
    // ====================================================================

    def "LogicSanityValidator empty steps returns REJECT"() {
        given:
        def v = new LogicSanityValidator()
        def plan = Plan.builder().type(Plan.Type.FAST_PATH).summary("empty").build()

        expect:
        !v.check(plan).isPassed()
    }

    def "LogicSanityValidator single step returns ALLOW"() {
        given:
        def v = new LogicSanityValidator()
        def plan = Plan.fastPath("test", "FINISH", "FINISH")

        expect:
        v.check(plan).isPassed()
    }

    def "LogicSanityValidator no cycle returns ALLOW"() {
        given:
        def v = new LogicSanityValidator()
        def plan = Plan.builder().type(Plan.Type.FAST_PATH).summary("normal")
            .addStep(Plan.Step.of("LLM_INFERENCE", "gpt-4o"))
            .addStep(Plan.Step.of("TOOL_CALL", "search_web"))
            .addStep(Plan.Step.of("FINISH", "FINISH"))
            .build()

        expect:
        v.check(plan).isPassed()
    }

    def "LogicSanityValidator detects cycle"() {
        given:
        def v = new LogicSanityValidator()
        def plan = Plan.builder().type(Plan.Type.FAST_PATH).summary("cycle")
            .addStep(Plan.Step.of("TOOL_CALL", "search"))
            .addStep(Plan.Step.of("TOOL_CALL", "search"))
            .addStep(Plan.Step.of("TOOL_CALL", "search"))
            .addStep(Plan.Step.of("TOOL_CALL", "search"))
            .addStep(Plan.Step.of("FINISH", "FINISH"))
            .build()

        expect:
        !v.check(plan).isPassed()
    }

    def "LogicSanityValidator multi-step without FINISH returns REJECT"() {
        given:
        def v = new LogicSanityValidator()
        def plan = Plan.builder().type(Plan.Type.FAST_PATH).summary("no-finish")
            .addStep(Plan.Step.of("LLM_INFERENCE", "gpt-4o"))
            .addStep(Plan.Step.of("TOOL_CALL", "search"))
            .build()

        expect:
        !v.check(plan).isPassed()
    }

    def "LogicSanityValidator REVISION as last step is allowed"() {
        given:
        def v = new LogicSanityValidator()
        def plan = Plan.builder().type(Plan.Type.FAST_PATH).summary("revision")
            .addStep(Plan.Step.of("LLM_INFERENCE", "gpt-4o"))
            .addStep(Plan.Step.of("REVISION", "REVISION"))
            .build()

        expect:
        v.check(plan).isPassed()
    }

    def "LogicSanityValidator single step without FINISH but FINISH action"() {
        given:
        def v = new LogicSanityValidator()
        def plan = Plan.builder().type(Plan.Type.FAST_PATH).summary("single")
            .addStep(Plan.Step.of("FINISH", "FINISH"))
            .build()

        expect:
        v.check(plan).isPassed()
    }

    // ====================================================================
    // ToolMicroLoopValidator (0%)
    // ====================================================================

    def "ToolMicroLoopValidator null registry throws"() {
        when: new ToolMicroLoopValidator(null)
        then: thrown(NullPointerException)
    }

    def "ToolMicroLoopValidator invalid thresholds throw"() {
        when: new ToolMicroLoopValidator(registryWith("t"), 0, 5, 3)
        then: thrown(IllegalArgumentException)
    }

    def "ToolMicroLoopValidator empty plan returns ALLOW"() {
        given:
        def v = new ToolMicroLoopValidator(registryWith("t"))
        def plan = Plan.builder().type(Plan.Type.FAST_PATH).summary("empty").build()

        expect:
        v.check(plan).isPassed()
    }

    def "ToolMicroLoopValidator no tool steps returns ALLOW"() {
        given:
        def v = new ToolMicroLoopValidator(registryWith("t"))
        def plan = Plan.fastPath("test", "LLM_INFERENCE", "gpt-4o")

        expect:
        v.check(plan).isPassed()
    }

    def "ToolMicroLoopValidator normal plan returns ALLOW"() {
        given:
        def v = new ToolMicroLoopValidator(registryWith("search_web"))
        def plan = Plan.builder().type(Plan.Type.FAST_PATH).summary("normal")
            .addStep(Plan.Step.of("TOOL_CALL", "search_web"))
            .addStep(Plan.Step.of("LLM_INFERENCE", "gpt-4o"))
            .addStep(Plan.Step.of("FINISH", "FINISH"))
            .build()

        expect:
        v.check(plan).isPassed()
    }

    def "ToolMicroLoopValidator detects plan-inner micro loop"() {
        given:
        def v = new ToolMicroLoopValidator(registryWith("search"), 5, 10, 3)
        def plan = Plan.builder().type(Plan.Type.FAST_PATH).summary("looping")
            .addStep(Plan.Step.of("TOOL_CALL", "search"))
            .addStep(Plan.Step.of("TOOL_CALL", "search"))
            .addStep(Plan.Step.of("TOOL_CALL", "search"))
            .addStep(Plan.Step.of("TOOL_CALL", "search"))
            .build()

        expect:
        !v.check(plan).isPassed()
    }

    def "ToolMicroLoopValidator detects exact repeat across history"() {
        given:
        def v = new ToolMicroLoopValidator(registryWith("search"), 1, 10, 5)
        v.recordCall("search", [q: "java"])
        def plan = Plan.builder().type(Plan.Type.FAST_PATH).summary("repeat")
            .addStep(Plan.Step.of("TOOL_CALL", "search", [q: "java"]))
            .build()

        expect:
        !v.check(plan).isPassed()
    }

    def "ToolMicroLoopValidator detects excessive total calls"() {
        given:
        def v = new ToolMicroLoopValidator(registryWith("search"), 5, 3, 5)
        v.recordCall("search", [q: "1"])
        v.recordCall("search", [q: "2"])
        v.recordCall("search", [q: "3"])
        def plan = Plan.builder().type(Plan.Type.FAST_PATH).summary("excessive")
            .addStep(Plan.Step.of("TOOL_CALL", "search", [q: "4"]))
            .build()

        expect:
        !v.check(plan).isPassed()
    }

    def "ToolMicroLoopValidator recordCall with null toolName throws"() {
        given:
        def v = new ToolMicroLoopValidator(registryWith("t"))

        when: v.recordCall(null)
        then: thrown(NullPointerException)
    }

    def "ToolMicroLoopValidator resetHistory clears records"() {
        given:
        def v = new ToolMicroLoopValidator(registryWith("t"))
        v.recordCall("tool1")
        v.recordCall("tool2")

        expect:
        v.historySize() == 2

        when:
        v.resetHistory()

        then:
        v.historySize() == 0
    }

    def "ToolMicroLoopValidator fingerprint generates stable keys"() {
        expect:
        ToolMicroLoopValidator.fingerprint("tool", [a: 1, b: 2]) == "tool::a=1|b=2"
        ToolMicroLoopValidator.fingerprint("tool", [:]) == "tool::(no-params)"
        ToolMicroLoopValidator.fingerprint("tool", [x: null]) == "tool::x=<null>"
    }

    def "ToolMicroLoopValidator skips unregistered tools"() {
        given:
        def v = new ToolMicroLoopValidator(registryWith("valid_tool"), 3, 10, 3)
        def plan = Plan.builder().type(Plan.Type.FAST_PATH).summary("unknown")
            .addStep(Plan.Step.of("TOOL_CALL", "unknown_tool"))
            .build()

        expect:
        v.check(plan).isPassed()
    }

    // ====================================================================
    // ToolResultValidator (0%)
    // ====================================================================

    def "ToolResultValidator null registry throws"() {
        when: new ToolResultValidator(null)
        then: thrown(NullPointerException)
    }

    def "ToolResultValidator empty plan returns ALLOW"() {
        given:
        def v = new ToolResultValidator(registryWith("t"))
        def plan = Plan.builder().type(Plan.Type.FAST_PATH).summary("empty").build()

        expect:
        v.check([:], plan).isPassed()
    }

    def "ToolResultValidator no TOOL_CALL steps returns ALLOW"() {
        given:
        def v = new ToolResultValidator(registryWith("t"))
        def plan = Plan.fastPath("inference", "LLM_INFERENCE", "gpt-4o")

        expect:
        v.check([:], plan).isPassed()
    }

    def "ToolResultValidator last TOOL_CALL with null target returns ALLOW"() {
        given:
        def v = new ToolResultValidator(registryWith("t"))
        def plan = Plan.builder().type(Plan.Type.FAST_PATH).summary("null-target")
            .addStep(Plan.Step.of("TOOL_CALL", null))
            .build()

        expect:
        v.check([:], plan).isPassed()
    }

    def "ToolResultValidator unregistered tool returns INVALID"() {
        given:
        def v = new ToolResultValidator(registryWith("registered_tool"))
        def plan = Plan.builder().type(Plan.Type.FAST_PATH).summary("unregistered")
            .addStep(Plan.Step.of("TOOL_CALL", "unknown_tool"))
            .build()

        expect:
        v.check([status: "SUCCESS", rawData: "ok"], plan).status() == AuditResult.Status.INVALID
    }



    // ====================================================================
    // AuditResult — 补充分支
    // ====================================================================

    def "AuditResult static factories"() {
        expect:
        AuditResult.allow().isPassed()
        AuditResult.allow().status() == AuditResult.Status.ALLOW

        AuditResult.allowWithWarning("warn").isPassed()
        AuditResult.allowWithWarning("warn").reason() == "warn"

        !AuditResult.reject("no", null).isPassed()
        AuditResult.reject("no", null).status() == AuditResult.Status.REJECT

        !AuditResult.invalid("bad", null).isPassed()
        AuditResult.invalid("bad", null).status() == AuditResult.Status.INVALID
    }

    def "AuditResult needsManualConfirm"() {
        expect:
        !AuditResult.allow().needsManualConfirm()
        AuditResult.allow().needsManualConfirm() == false

        AuditResult.manualConfirm("manual confirm").needsManualConfirm()
    }

    def "AuditResult builder edge cases"() {
        when:
        def r = AuditResult.builder()
            .status(AuditResult.Status.ALLOW)
            .risk(org.cland.alice.guardrail.RiskLevel.LOW)
            .reason("test")
            .build()

        then:
        r.status() == AuditResult.Status.ALLOW
        r.reason() == "test"
        r.toString().contains("ALLOW")
    }

    // ====================================================================
    // CorrectionSuggestion — 补充分支
    // ====================================================================

    def "CorrectionSuggestion static factories"() {
        expect:
        CorrectionSuggestion.replan("replan").type() == CorrectionSuggestion.Type.REPLAN
        CorrectionSuggestion.manualConfirm("manual").type() == CorrectionSuggestion.Type.MANUAL_CONFIRM
        CorrectionSuggestion.modifyParameters("mod", [key: "val"]).type() == CorrectionSuggestion.Type.MODIFY_PARAMETERS
        CorrectionSuggestion.changeTarget("newTarget").type() == CorrectionSuggestion.Type.CHANGE_TARGET
        CorrectionSuggestion.gatherContext("more").type() == CorrectionSuggestion.Type.GATHER_CONTEXT
    }

    def "CorrectionSuggestion builder"() {
        when:
        def cs = CorrectionSuggestion.builder()
            .type(CorrectionSuggestion.Type.REPLAN)
            .description("try again")
            .build()

        then:
        cs.type() == CorrectionSuggestion.Type.REPLAN
        cs.description() == "try again"
    }

    def "CorrectionSuggestion abort"() {
        expect:
        CorrectionSuggestion.abort("stop").type() == CorrectionSuggestion.Type.ABORT
        CorrectionSuggestion.abort("stop").description() == "stop"
    }

    def "CorrectionSuggestion toString"() {
        expect:
        CorrectionSuggestion.replan("test").toString().contains("REPLAN")
    }

    // ====================================================================
    // Verificator — 接口默认实现
    // ====================================================================

    def "Verificator default intercept returns true"() {
        given:
        def v = new Verificator() {}

        expect:
        v.intercept([type: "TOOL_CALL"])
        v.audit("any result")
    }
}
