package org.cland.alice.memory

import org.cland.alice.memory.agent.Context
import org.cland.alice.memory.controller.VaultController
import org.cland.alice.memory.core.Experience
import org.cland.alice.memory.core.Knowledge
import org.cland.alice.memory.core.MemorySet
import org.cland.alice.memory.core.SOP
import org.cland.alice.memory.core.Step
import org.cland.alice.memory.core.Summary
import org.cland.alice.memory.storage.InMemoryStorageBackend
import org.cland.alice.memory.vault.InMemoryEpisodicVault
import org.cland.alice.memory.vault.InMemoryProceduralVault
import org.cland.alice.memory.vault.InMemorySemanticVault
import org.cland.alice.memory.router.DefaultMemorySummarizer
import org.cland.alice.memory.router.MemoryRouter

import spock.lang.Specification
import spock.lang.Subject

/**
 * Spock 测试：验证 alice-memory-vault 的三级记忆架构。
 * <p>
 * 测试覆盖：
 * <ul>
 *   <li>VaultController.recall / memorize</li>
 *   <li>MemoryRouter 路由逻辑</li>
 *   <li>EpisodicVault 遗忘策略</li>
 *   <li>SemanticVault 检索与 Collection 隔离</li>
 *   <li>ProceduralVault 模式匹配</li>
 *   <li>Memory Consolidation（finalizeSession）</li>
 * </ul>
 */
class MemoryVaultSpec extends Specification {

    @Subject
    VaultController vault

    def setup() {
        vault = new VaultController()
    }

    // ---------------------------------------------------------------
    // 1. 基础 API：memorize + recall
    // ---------------------------------------------------------------

    def "should memorize experience and recall it from episodic memory"() {
        given: "a session with one experience"
        def sessionId = "test-session-1"
        vault.memorize(Experience.builder()
                .sessionId(sessionId)
                .action("search")
                .observation("查找 Java 25 新特性")
                .result("找到 5 条相关结果")
                .build())

        when: "recall with session context"
        def ctx = Context.builder()
                .query("刚才做了什么？")
                .sessionId(sessionId)
                .build()
        def result = vault.recall(ctx)

        then: "should return episodic memory entry"
        result.size() > 0
        result.entries().stream().anyMatch(e ->
                e.vaultType() == MemorySet.VaultType.EPISODIC)
    }

    // ---------------------------------------------------------------
    // 2. SemanticVault：知识存取与检索
    // ---------------------------------------------------------------

    def "should store and retrieve semantic knowledge"() {
        given: "a VaultController with pre-seeded semantic knowledge"
        def semantic = new InMemorySemanticVault()
        semantic.store("java",
                Knowledge.builder()
                        .knowledgeId("java-25-records")
                        .content("Java 25 增强了 Record 模式匹配，支持嵌套解构")
                        .source("docs")
                        .collection("java")
                        .build())
        vault = new VaultController(
                new InMemoryEpisodicVault(), semantic, new InMemoryProceduralVault(),
                new InMemoryStorageBackend(), new DefaultMemorySummarizer())

        when: "search with related query"
        def ctx = Context.builder()
                .query("什么是 Java 25 的 Record 模式匹配？")
                .metadata("collection", "java")
                .build()
        def result = vault.recall(ctx)

        then: "should find the knowledge"
        result.size() > 0
        result.entries().stream().anyMatch(e ->
                e.vaultType() == MemorySet.VaultType.SEMANTIC)
    }

    def "should isolate collections to avoid cross-collection noise"() {
        given: "a VaultController with two collections seeded"
        def semantic = new InMemorySemanticVault()
        semantic.store("project-alpha",
                Knowledge.builder()
                        .knowledgeId("alpha-api-v1")
                        .content("Project-Alpha 私有 API：/api/v1/internal/secret")
                        .build())
        semantic.store(Knowledge.builder()
                .knowledgeId("spring-boot-docs")
                .content("Spring Boot 3.4 提供了虚拟线程支持")
                .build())
        vault = new VaultController(
                new InMemoryEpisodicVault(), semantic, new InMemoryProceduralVault(),
                new InMemoryStorageBackend(), new DefaultMemorySummarizer())

        when: "search only in default collection"
        def ctx = Context.builder()
                .query("什么是 Spring Boot？")
                .metadata("collection", "_default")
                .build()
        def result = vault.recall(ctx)

        then: "should not leak Project-Alpha secrets"
        result.entries().stream().noneMatch(e ->
                e.vaultType() == MemorySet.VaultType.SEMANTIC &&
                        ((MemorySet.SemanticEntry) e).knowledgeId() == "alpha-api-v1")
    }

    // ---------------------------------------------------------------
    // 3. ProceduralVault：SOP 模式匹配
    // ---------------------------------------------------------------

    def "should match SOP by tool name"() {
        given: "a VaultController with pre-registered SOP"
        def procedural = new InMemoryProceduralVault()
        procedural.register(SOP.builder()
                .sopId("git-commit-sop")
                .name("Git 提交规范")
                .pattern("git commit")
                .procedure("1. git add .\n2. git commit -m 'feat: ...'\n3. git push")
                .toolName("git")
                .build())
        vault = new VaultController(
                new InMemoryEpisodicVault(), new InMemorySemanticVault(), procedural,
                new InMemoryStorageBackend(), new DefaultMemorySummarizer())

        when: "query about how to use git"
        def ctx = Context.builder()
                .query("如何使用 git commit？")
                .build()
        def result = vault.recall(ctx)

        then: "should match the git SOP"
        result.size() > 0
        result.entries().stream().anyMatch(e ->
                e.vaultType() == MemorySet.VaultType.PROCEDURAL &&
                        ((MemorySet.ProceduralEntry) e).sopId() == "git-commit-sop")
    }

    // ---------------------------------------------------------------
    // 4. EpisodicVault 遗忘策略
    // ---------------------------------------------------------------

    def "should forget low-importance steps when limit exceeded"() {
        given: "a vault with max 3 steps per session"
        def episodic = new InMemoryEpisodicVault(3, 10)

        when: "append 5 steps with varying importance"
        (1..5).each { i ->
            episodic.appendStep("session-A", Step.builder()
                    .stepId("step-$i")
                    .action("action-$i")
                    .input("input-$i")
                    .output("output-$i")
                    .timestamp(System.currentTimeMillis())
                    .importance(i * 0.1)
                    .build())
        }

        then: "only the 3 most important steps remain"
        episodic.stepCount("session-A") == 3
        def remaining = episodic.getTrace("session-A")
        remaining.every { it.importance() >= 0.3 }
    }

    def "should evict least recently used sessions"() {
        given: "a vault with max 2 sessions"
        def episodic = new InMemoryEpisodicVault(10, 2)

        when: "add 3 sessions"
        (1..3).each { i ->
            episodic.appendStep("session-$i", Step.builder()
                    .stepId("step-1")
                    .action("action")
                    .input("input")
                    .output("output")
                    .timestamp(System.currentTimeMillis())
                    .build())
        }

        then: "only 1 session remains"
        episodic.sessionCount() == 1
    }

    // ---------------------------------------------------------------
    // 5. MemoryRouter 路由逻辑
    // ---------------------------------------------------------------

    def "should route episodic query to episodic vault"() {
        given: "a session with some data"
        vault.memorize(Experience.builder()
                .sessionId("route-test")
                .action("analyze")
                .observation("分析数据")
                .result("完成分析")
                .build())

        when: "query about what just happened"
        def ctx = Context.builder()
                .query("刚才做了什么？")
                .sessionId("route-test")
                .build()
        def result = vault.recall(ctx)

        then: "should contain episodic entries"
        result.entries().stream().anyMatch(e ->
                e.vaultType() == MemorySet.VaultType.EPISODIC)
    }

    def "should route procedural query to procedural vault"() {
        given: "a VaultController with pre-registered SOP"
        def procedural = new InMemoryProceduralVault()
        procedural.register(SOP.builder()
                .sopId("build-sop")
                .name("构建流程")
                .pattern("gradle build")
                .procedure("./gradlew build")
                .toolName("gradle")
                .build())
        vault = new VaultController(
                new InMemoryEpisodicVault(), new InMemorySemanticVault(), procedural,
                new InMemoryStorageBackend(), new DefaultMemorySummarizer())

        when: "query about how to build"
        def ctx = Context.builder()
                .query("如何执行 gradle build？")
                .build()
        def result = vault.recall(ctx)

        then: "should contain procedural entries"
        result.entries().stream().anyMatch(e ->
                e.vaultType() == MemorySet.VaultType.PROCEDURAL)
    }

    // ---------------------------------------------------------------
    // 6. Memory Consolidation（finalizeSession）
    // ---------------------------------------------------------------

    def "should consolidate session into semantic and procedural memory"() {
        given: "a session with multiple successful steps"
        def sessionId = "consolidation-test"

        (1..5).each { i ->
            vault.memorize(Experience.builder()
                    .sessionId(sessionId)
                    .action("step-$i")
                    .observation("input-$i")
                    .result("output-$i (success)")
                    .timestamp(System.currentTimeMillis() + i)
                    .build())
        }

        when: "finalize the session"
        def summaryFuture = vault.finalizeSession(sessionId)
        def summary = summaryFuture.get()

        then: "summary should contain facts and patterns"
        summary.stepCount() == 5
        summary.sessionId() == sessionId
        summary.facts().size() > 0
        summary.successPatterns().size() > 0
    }

    // ---------------------------------------------------------------
    // 7. Penalize Step（遗忘策略——权重降低）
    // ---------------------------------------------------------------

    def "should penalize a step and reduce its importance"() {
        given: "a step in episodic vault"
        def episodic = new InMemoryEpisodicVault()
        episodic.appendStep("session-penalty", Step.builder()
                .stepId("wrong-step")
                .action("wrong-action")
                .input("bad-input")
                .output("ERROR: failed")
                .importance(0.8)
                .build())

        when: "penalize the step"
        episodic.penalizeStep("session-penalty", "wrong-step", 0.5)

        then: "importance should be reduced to approximately 0.3"
        def steps = episodic.getTrace("session-penalty")
        Math.abs(steps[0].importance() - 0.3) < 0.0001
    }

    // ---------------------------------------------------------------
    // 8. Context 路由判断
    // ---------------------------------------------------------------

    def "context should correctly detect query type"() {
        expect:
        Context.builder().query(query).build().isEpisodicQuery() == episodic
        Context.builder().query(query).build().isSemanticQuery() == semantic
        Context.builder().query(query).build().isProceduralQuery() == procedural

        where:
        query                          | episodic | semantic | procedural
        "刚才做了什么？"               | true     | false    | false
        "什么是 Java 25？"             | false    | true     | false
        "如何执行 git commit？"        | false    | false    | true
        "what is a record pattern?"    | false    | true     | false
        "how to build the project?"    | false    | false    | true
        "之前的会话结果"               | true     | false    | false
        "解释一下这个工具的使用"       | false    | true     | true
        "hello world"                  | false    | false    | false
    }

    // ---------------------------------------------------------------
    // 9. StorageBackend 基本操作
    // ---------------------------------------------------------------

    def "in-memory storage backend should support CRUD"() {
        given:
        def storage = new InMemoryStorageBackend()

        when: "write and read"
        storage.put("key1", "value1".bytes)
        def val = storage.get("key1")

        then: "should return the stored value"
        new String(val) == "value1"
        storage.exists("key1")

        when: "delete"
        storage.delete("key1")

        then: "should be gone"
        !storage.exists("key1")
        storage.get("key1") == null
    }
}
