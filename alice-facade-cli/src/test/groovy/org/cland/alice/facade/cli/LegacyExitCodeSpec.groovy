package org.cland.alice.facade.cli

import org.cland.alice.core.agent.AgentContext
import spock.lang.Specification
import spock.lang.Unroll

/**
 * #317 负例回归：执行失败必须非零退出，不得“吞错返回成功”。
 *
 * <p>退出码在 {@link ExecutionCoordinator#execute()} 中由 {@code resolveSuccess(ctx)} 派生：
 * {@code Boolean.FALSE ⇒ return 1}；因此这里的每个 false 断言都等价于“失败 → exit≠0”。
 */
class LegacyExitCodeSpec extends Specification {

    private static AgentContext ctx(Map<String, Object> attrs) {
        def c = new AgentContext("test-session")
        attrs.each { k, v -> c.put(k, v) }
        return c
    }

    @Unroll
    def "non-FINISHED status '#status' 判失败（⇒ 非零退出）"() {
        expect:
        ExecutionCoordinator.resolveSuccess(ctx([status: status])) == false
        ExecutionCoordinator.exitCodeFor(ExecutionCoordinator.resolveSuccess(ctx([status: status]))) != 0

        where:
        status << ["FAILED", "CANCELLED", "FATAL_ERROR", "TIMEOUT", "BOGUS"]
    }

    def "失败上下文 → exitCodeFor 返回 1（活生生的非零退出）"() {
        expect:
        ExecutionCoordinator.exitCodeFor(ExecutionCoordinator.resolveSuccess(ctx([status: "FAILED"]))) == 1
        ExecutionCoordinator.exitCodeFor(ExecutionCoordinator.resolveSuccess(ctx([error: "boom"]))) == 1
    }

    def "FINISHED 判成功（⇒ exit 0）"() {
        expect:
        ExecutionCoordinator.resolveSuccess(ctx([status: "FINISHED"])) == true
    }

    def "显式 success=false 覆盖 FINISHED（失败不吞）"() {
        expect:
        ExecutionCoordinator.resolveSuccess(ctx([status: "FINISHED", success: false])) == false
    }

    def "settled=false 即使带 success=true 也判失败"() {
        expect:
        ExecutionCoordinator.resolveSuccess(ctx([settled: false, success: true, status: "FINISHED"])) == false
    }

    def "仅 error 键判失败"() {
        expect:
        ExecutionCoordinator.resolveSuccess(ctx([error: "no model supplier"])) == false
    }

    def "无终态信号返回 null（沿用旧行为，不臆造成功）"() {
        expect:
        ExecutionCoordinator.resolveSuccess(ctx([:])) == null
        ExecutionCoordinator.resolveSuccess(null) == null
    }
}
