package org.cland.alice.guardrail.validators

import org.cland.alice.core.planner.Plan
import org.cland.alice.guardrail.AuditResult
import spock.lang.Specification
import spock.lang.Unroll

/**
 * probe 独立负控/正控（#522）——不依赖作者 spec，单列验证结构性判定。
 * 负控：不应误判为 INVALID（读取源码/裸词）
 * 正控：真实错误/空结果/FAILURE 必须 INVALID
 */
class ProbeHallucinationDetectorSpec extends Specification {
    def detector = new HallucinationDetector()
    def plan = Plan.fastPath("search", Plan.Intent.SEARCH, "web")

    def obs(String status, String raw, String tool) {
        def m = ["status": status, "summary": "obs", "rawData": raw] as Map<String, Object>
        if (tool != null) m["metadata"] = ["toolName": tool] as Map<String, Object>
        return m
    }

    // ── 负控：ALLOW ──────────────────────────────────────────────
    def "N1 只读工具 read_file 读含 null/undefined/not found/timeout 的源码 => ALLOW"() {
        expect:
        detector.check(obs("SUCCESS", "Error: not used\nv = null\nu = undefined\n# not found\ntimeout = 30\nTimeout in ms\n", "read_file"), plan).passed
    }

    def "N2 执行工具输出仅含裸词 null/undefined/not found/timeout= => ALLOW"() {
        expect:
        detector.check(obs("SUCCESS", "cfg null\nflag undefined\n# not found here\ntimeout = 30\n", "run_command"), plan).passed
    }

    def "N3 只读工具 grep 结果含 'not found' 文案 => ALLOW"() {
        expect:
        detector.check(obs("SUCCESS", "3 matches; 1 file not found in index", "grep"), plan).passed
    }

    def "N4 正常 JSON 观测 => ALLOW"() {
        expect:
        detector.check(obs("SUCCESS", '{"ok": true, "items": [1,2,3]}', "run_command"), plan).passed
    }

    // ── 正控：INVALID ────────────────────────────────────────────
    @Unroll
    def "P status=FAILURE 必 INVALID (tool=#tool)"() {
        expect:
        detector.check(obs("FAILURE", "boom", tool), plan).status == AuditResult.Status.INVALID
        where:
        tool << ["read_file", "run_command", null]
    }

    @Unroll
    def "P 执行工具真实错误/空结果文案 INVALID: #desc"() {
        expect:
        detector.check(obs("SUCCESS", raw, "run_command"), plan).status == AuditResult.Status.INVALID
        where:
        desc              | raw
        "timed out"        | "request timed out after 30s"
        "no results found" | "no results found for query"
        "Traceback"        | "Traceback (most recent call last):\n  File \"x.py\""
        "command not found"| "bash: foo: command not found"
        "connection refused"| "Error: connection refused to host"
        "no such file"     | "cat: /x: No such file or directory"
    }

    // ── 边界（记录观察，非判定） ────────────────────────────────
    def "B1 只读工具内容即使含 Traceback 也按数据 ALLOW（工具类型门边界）"() {
        expect:
        detector.check(obs("SUCCESS", "Traceback (most recent call last):\n", "read_file"), plan).passed
    }
}
