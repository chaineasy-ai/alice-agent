package org.cland.alice.memory

import spock.lang.Specification

/**
 * 补充测试：覆盖边界场景、异常路径、数据模型行为。
 * <p>
 * 测试覆盖：
 * <ul>
 *   <li>MemorySet 构建、迭代、空集合</li>
 *   <li>Context null 拒绝、equals/hashCode/toString、metadata 类型造型</li>
 *   <li>EpisodicVault 边界：空 session、getImportantSteps、clear 操作</li>
 *   <li>SemanticVault 边界：storeAll、remove、clear、未知 collection</li>
 *   <li>ProceduralVault 边界：批量注册、更新、清除、空匹配</li>
 *   <li>DefaultMemorySummarizer 边界：null trace、混合成功/失败、短序列</li>
 *   <li>VaultController 边界：null 参数、ERROR 重要度、多参数构造</li>
 *   <li>值对象：Step、Experience、Knowledge、SOP、Summary 的构建与 toString</li>
 * </ul>
 */
class MemoryVaultEdgeCaseSpec extends Specification {

    VaultController vault

    def setup() {
        vault = new VaultController()
    }

    // ---------------------------------------------------------------
    // 1. MemorySet 边缘场景
    // ---------------------------------------------------------------

    def "MemorySet.empty() should return empty set"() {
        when:
        def set = MemorySet.Builder.empty()

        then:
        set.isEmpty()
        set.size() == 0
    }

    def "MemorySet.builder() empty build should return empty set"() {
        when:
        def set = MemorySet.builder().build()

        then:
        set.isEmpty()
        set.size() == 0
    }

    def "MemorySet should iterate with for-each"() {
        given:
        def set = MemorySet.builder()
                .addEpisodic("s1", "content", 1000L, 0.8)
                .addSemantic("k1", "knowledge", 0.9)
                .addProcedural("p1", "pattern", "procedure", 0.7)
                .build()

        when:
        def types = []
        set.each { types << it.vaultType() }

        then:
        types == [
            MemorySet.VaultType.EPISODIC,
            MemorySet.VaultType.SEMANTIC,
            MemorySet.VaultType.PROCEDURAL
        ]
    }

    def "MemorySet.Entries should carry correct data"() {
        when:
        def episodic = new MemorySet.EpisodicEntry("sid", "hello", 123L, 0.5)
        def semantic = new MemorySet.SemanticEntry("kid", "world", 0.8)
        def procedural = new MemorySet.ProceduralEntry("pid", "pat", "proc", 0.9)

        then:
        episodic.sessionId() == "sid"
        episodic.content() == "hello"
        episodic.timestamp() == 123L
        episodic.score() == 0.5
        episodic.vaultType() == MemorySet.VaultType.EPISODIC

        semantic.knowledgeId() == "kid"
        semantic.content() == "world"
        semantic.score() == 0.8
        semantic.vaultType() == MemorySet.VaultType.SEMANTIC

        procedural.sopId() == "pid"
        procedural.pattern() == "pat"
        procedural.procedure() == "proc"
        procedural.score() == 0.9
        procedural.vaultType() == MemorySet.VaultType.PROCEDURAL
    }

    def "MemorySet.Builder.addAll should merge entries"() {
        given:
        def first = MemorySet.builder()
                .addEpisodic("s1", "c1", 1L, 0.1)
                .build()

        when:
        def merged = MemorySet.builder()
                .addAll(first.entries())
                .addSemantic("k2", "c2", 0.2)
                .build()

        then:
        merged.size() == 2
    }

    // ---------------------------------------------------------------
    // 2. Context 边缘场景
    // ---------------------------------------------------------------

    def "Context.builder should reject null query"() {
        when:
        Context.builder().build()

        then:
        thrown(NullPointerException)
    }

    def "Context metadata with Map argument"() {
        given:
        def meta = ["key1": "val1", "key2": 42]
        def ctx = Context.builder()
                .query("test")
                .metadata(meta)
                .build()

        expect:
        ctx.metadata("key1") == "val1"
        ctx.metadata("key2") == 42
        ctx.metadata().size() == 2
    }

    def "Context equals and hashCode"() {
        given:
        def a = Context.builder().query("q").sessionId("s").metadata("k", "v").build()
        def b = Context.builder().query("q").sessionId("s").metadata("k", "v").build()
        def c = Context.builder().query("q2").sessionId("s").build()

        expect:
        a == b
        a.hashCode() == b.hashCode()
        a != c
        a.hashCode() != c.hashCode()
    }

    def "Context toString should contain query and sessionId"() {
        given:
        def ctx = Context.builder().query("hello").sessionId("s1").build()

        expect:
        ctx.toString().contains("hello")
        ctx.toString().contains("s1")
    }

    def "Context metadata typed accessor"() {
        given:
        def ctx = Context.builder()
                .query("test")
                .metadata("count", 99)
                .build()

        // metadata 返回 Map<String, Object>，类型转换由调用方负责
        expect:
        (ctx.metadata("count") as Integer) == 99
    }

    // ---------------------------------------------------------------
    // 3. EpisodicVault 边缘场景
    // ---------------------------------------------------------------

    def "EpisodicVault should handle empty session"() {
        given:
        def vault = new EpisodicVault()

        expect:
        vault.getTrace("nonexistent").isEmpty()
        vault.getRecentSteps("nonexistent", 5).isEmpty()
        vault.getImportantSteps("nonexistent", 0.5).isEmpty()
        vault.stepCount("nonexistent") == 0
        vault.getActiveSessionIds().isEmpty()
    }

    def "EpisodicVault getImportantSteps should filter by importance threshold"() {
        given:
        def vault = new EpisodicVault()
        vault.appendStep("s", Step.builder().stepId("s1").importance(0.9).build())
        vault.appendStep("s", Step.builder().stepId("s2").importance(0.3).build())
        vault.appendStep("s", Step.builder().stepId("s3").importance(0.1).build())

        when:
        def important = vault.getImportantSteps("s", 0.5)

        then:
        important.size() == 1
        important[0].stepId() == "s1"
    }

    def "EpisodicVault clearSession should remove only that session"() {
        given:
        def vault = new EpisodicVault()
        vault.appendStep("s1", Step.builder().stepId("a").build())
        vault.appendStep("s2", Step.builder().stepId("b").build())

        when:
        vault.clearSession("s1")

        then:
        vault.stepCount("s1") == 0
        vault.stepCount("s2") == 1
        vault.sessionCount() == 1
    }

    def "EpisodicVault clearAll should remove everything"() {
        given:
        def vault = new EpisodicVault()
        vault.appendStep("s1", Step.builder().stepId("a").build())
        vault.appendStep("s2", Step.builder().stepId("b").build())

        when:
        vault.clearAll()

        then:
        vault.sessionCount() == 0
        vault.getActiveSessionIds().isEmpty()
    }

    def "EpisodicVault getRecentSteps should return last N steps"() {
        given:
        def vault = new EpisodicVault()
        (1..10).each { i ->
            vault.appendStep("s", Step.builder().stepId("step-$i").build())
        }

        when:
        def recent = vault.getRecentSteps("s", 3)

        then:
        recent.size() == 3
        recent[0].stepId() == "step-8"
        recent[2].stepId() == "step-10"
    }

    def "EpisodicVault penalizeStep should handle nonexistent step or session"() {
        given:
        def vault = new EpisodicVault()
        vault.appendStep("s1", Step.builder().stepId("step1").importance(0.8).build())

        when: "penalize nonexistent stepId in existing session"
        vault.penalizeStep("s1", "nobody", 0.3)

        then: "no exception, existing step unchanged"
        vault.getTrace("s1")[0].importance() == 0.8

        when: "penalize step in nonexistent session"
        vault.penalizeStep("nosession", "step1", 0.3)

        then: "no exception"
        noExceptionThrown()
    }

    def "EpisodicVault.getActiveSessionIds should return all session IDs"() {
        given:
        def vault = new EpisodicVault()
        vault.appendStep("s1", Step.builder().stepId("a").build())
        vault.appendStep("s2", Step.builder().stepId("b").build())
        vault.appendStep("s3", Step.builder().stepId("c").build())

        when:
        def ids = vault.getActiveSessionIds()

        then:
        ids.size() == 3
        ids.containsAll(["s1", "s2", "s3"])
    }

    // ---------------------------------------------------------------
    // 4. SemanticVault 边缘场景
    // ---------------------------------------------------------------

    def "SemanticVault storeAll should store multiple knowledge items"() {
        given:
        def vault = new SemanticVault()
        def list = [
            Knowledge.builder().knowledgeId("k1").content("Java 25").build(),
            Knowledge.builder().knowledgeId("k2").content("Virtual Threads").build()
        ]

        when:
        vault.storeAll("tech", list)

        then:
        vault.count("tech") == 2
    }

    def "SemanticVault remove should delete specific knowledge"() {
        given:
        def vault = new SemanticVault()
        vault.store("coll", Knowledge.builder().knowledgeId("k1").content("a").build())
        vault.store("coll", Knowledge.builder().knowledgeId("k2").content("b").build())

        expect:
        vault.count("coll") == 2

        when:
        def removed = vault.remove("coll", "k1")

        then:
        removed
        vault.count("coll") == 1
        vault.getAll("coll")[0].knowledgeId() == "k2"
    }

    def "SemanticVault remove with nonexistent collection or id should return false"() {
        given:
        def vault = new SemanticVault()
        vault.store("c", Knowledge.builder().knowledgeId("k1").content("x").build())

        expect:
        !vault.remove("nonexistent", "k1")
        !vault.remove("c", "nobody")
    }

    def "SemanticVault removeCollection should delete entire collection"() {
        given:
        def vault = new SemanticVault()
        vault.store("c1", Knowledge.builder().knowledgeId("k1").content("a").build())
        vault.store("c2", Knowledge.builder().knowledgeId("k2").content("b").build())

        when:
        vault.removeCollection("c1")

        then:
        vault.getCollections() == ["c2"]
    }

    def "SemanticVault clearAll should remove all collections"() {
        given:
        def vault = new SemanticVault()
        vault.store("c1", Knowledge.builder().knowledgeId("k1").content("a").build())

        when:
        vault.clearAll()

        then:
        vault.getCollections().isEmpty()
        vault.count("c1") == 0
    }

    def "SemanticVault getAll should return empty for unknown collection"() {
        expect:
        new SemanticVault().getAll("unknown").isEmpty()
    }

    def "SemanticVault search with unknown collection should return empty"() {
        expect:
        new SemanticVault().search("unknown", "query").isEmpty()
    }

    def "SemanticVault searchAll with no matches should return empty"() {
        given:
        def vault = new SemanticVault()
        vault.store("c", Knowledge.builder().knowledgeId("k1").content("abcdefghijklmnop").build())

        expect:
        vault.searchAll("xyz").isEmpty()
    }

    def "SemanticVault with custom topK and threshold"() {
        given:
        def vault = new SemanticVault(3, 0.0)
        vault.store("c", Knowledge.builder().knowledgeId("k1").content("Java 25 features").build())
        vault.store("c", Knowledge.builder().knowledgeId("k2").content("Python basics").build())
        vault.store("c", Knowledge.builder().knowledgeId("k3").content("Rust ownership").build())
        vault.store("c", Knowledge.builder().knowledgeId("k4").content("Go routines").build())

        when:
        def results = vault.search("c", "Java")

        then: "topK=3 limits to 3 results"
        results.size() == 3
    }

    def "SemanticVault store with null or blank collection uses _default"() {
        given:
        def vault = new SemanticVault()

        when:
        vault.store(Knowledge.builder().knowledgeId("k1").content("x").collection(null).build())
        vault.store(Knowledge.builder().knowledgeId("k2").content("y").collection("").build())

        then:
        vault.count("_default") == 2
    }

    // ---------------------------------------------------------------
    // 5. ProceduralVault 边缘场景
    // ---------------------------------------------------------------

    def "ProceduralVault registerAll should batch register"() {
        given:
        def vault = new ProceduralVault()
        def sops = [
            SOP.builder().sopId("s1").name("SOP1").pattern("p1").procedure("proc1").build(),
            SOP.builder().sopId("s2").name("SOP2").pattern("p2").procedure("proc2").build()
        ]

        when:
        vault.registerAll(sops)

        then:
        vault.count() == 2
    }

    def "ProceduralVault update existing SOP should replace it"() {
        given:
        def vault = new ProceduralVault()
        vault.register(SOP.builder()
                .sopId("s1").name("Old").pattern("old").procedure("old proc")
                .version("1.0").build())

        when:
        vault.register(SOP.builder()
                .sopId("s1").name("New").pattern("new").procedure("new proc")
                .version("2.0").build())

        then:
        vault.count() == 1
        vault.getById("s1").name() == "New"
        vault.getById("s1").version() == "2.0"
    }

    def "ProceduralVault findByTool should return matching SOPs"() {
        given:
        def vault = new ProceduralVault()
        vault.register(SOP.builder().sopId("g1").name("Git").pattern("git").procedure("p").toolName("git").build())
        vault.register(SOP.builder().sopId("g2").name("GitHub").pattern("gh").procedure("p").toolName("git").build())
        vault.register(SOP.builder().sopId("m1").name("Maven").pattern("mvn").procedure("p").toolName("maven").build())

        when:
        def gitSops = vault.findByTool("git")
        def unknownSops = vault.findByTool("nonexistent")

        then:
        gitSops.size() == 2
        unknownSops.isEmpty()
    }

    def "ProceduralVault getById should return null for nonexistent SOP"() {
        expect:
        new ProceduralVault().getById("nobody") == null
    }

    def "ProceduralVault getAll should return all SOPs"() {
        given:
        def vault = new ProceduralVault()
        vault.register(SOP.builder().sopId("a").name("A").pattern("a").procedure("a").build())
        vault.register(SOP.builder().sopId("b").name("B").pattern("b").procedure("b").build())

        when:
        def all = vault.getAll()

        then:
        all.size() == 2
    }

    def "ProceduralVault remove should delete specific SOP"() {
        given:
        def vault = new ProceduralVault()
        vault.register(SOP.builder().sopId("a").name("A").pattern("a").procedure("a").build())
        vault.register(SOP.builder().sopId("b").name("B").pattern("b").procedure("b").build())

        when:
        def removed = vault.remove("a")

        then:
        removed
        vault.count() == 1
        vault.getById("a") == null

        and:
        !vault.remove("nonexistent")
    }

    def "ProceduralVault clearAll should remove everything"() {
        given:
        def vault = new ProceduralVault()
        vault.register(SOP.builder().sopId("a").name("A").pattern("a").procedure("a").build())

        when:
        vault.clearAll()

        then:
        vault.count() == 0
        vault.getAll().isEmpty()
    }

    def "ProceduralVault match with no matching context should return empty"() {
        given:
        def vault = new ProceduralVault()
        vault.register(SOP.builder()
                .sopId("s1").name("Gradle Build")
                .pattern("gradle build")
                .procedure("./gradlew build")
                .toolName("gradle")
                .build())

        when:
        def result = vault.match(Context.builder().query("如何煮咖啡？").build())

        then:
        result.isEmpty()
    }

    def "ProceduralVault match with topK limit"() {
        given:
        def vault = new ProceduralVault(2)
        (1..5).each { i ->
            vault.register(SOP.builder()
                    .sopId("sop-$i").name("Tool-$i")
                    .pattern("tool-$i")
                    .procedure("use tool-$i")
                    .toolName("tool-$i")
                    .build())
        }

        when:
        def result = vault.match(Context.builder().query("tool").build())

        then:
        result.size() <= 2
    }

    // ---------------------------------------------------------------
    // 6. DefaultMemorySummarizer 边缘场景
    // ---------------------------------------------------------------

    def "DefaultMemorySummarizer should handle null trace"() {
        given:
        def summarizer = new DefaultMemorySummarizer()

        when:
        def summary = summarizer.summarize(null)

        then:
        summary.stepCount() == 0
        summary.facts().isEmpty()
        summary.successPatterns().isEmpty()
        summary.sessionId() == "unknown"
    }

    def "DefaultMemorySummarizer should handle empty trace"() {
        given:
        def summarizer = new DefaultMemorySummarizer()

        when:
        def summary = summarizer.summarize([])

        then:
        summary.stepCount() == 0
        summary.facts().isEmpty()
        summary.successPatterns().isEmpty()
    }

    def "DefaultMemorySummarizer should extract no patterns from short success runs"() {
        given:
        def steps = (1..2).collect { i ->
            Step.builder()
                    .stepId("s$i")
                    .action("step-$i")
                    .input("in-$i")
                    .output("out-$i")
                    .success(true)
                    .importance(0.8)
                    .build()
        }

        when:
        def summary = new DefaultMemorySummarizer().summarize(steps)

        then: "facts exist but no pattern (need 3+ consecutive)"
        summary.facts().size() == 2
        summary.successPatterns().isEmpty()
    }

    def "DefaultMemorySummarizer should handle mixed success/failure"() {
        given:
        def steps = [
            Step.builder().stepId("1").action("a1").success(true).importance(0.8).build(),
            Step.builder().stepId("2").action("a2").success(true).importance(0.8).build(),
            Step.builder().stepId("3").action("a3").success(true).importance(0.8).build(),
            Step.builder().stepId("4").action("a4").success(false).importance(0.8).build(),
            Step.builder().stepId("5").action("a5").success(true).importance(0.8).build(),
            Step.builder().stepId("6").action("a6").success(true).importance(0.8).build(),
            Step.builder().stepId("7").action("a7").success(true).importance(0.8).build(),
        ]

        when:
        def summary = new DefaultMemorySummarizer().summarize(steps)

        then: "two patterns: steps 1-3 and steps 5-7"
        summary.successPatterns().size() == 2
        // 6 successful steps (step 4 is failure), all with importance 0.8 >= 0.3
        summary.facts().size() == 6
    }

    def "DefaultMemorySummarizer deduplicates facts"() {
        given:
        def steps = [
            Step.builder().stepId("1").action("search").input("query").output("result1").success(true).importance(0.8).build(),
            Step.builder().stepId("2").action("search").input("query").output("result2").success(true).importance(0.8).build(),
        ]

        when:
        def summary = new DefaultMemorySummarizer().summarize(steps)

        then: "same action+input de-duplicated"
        summary.facts().size() == 1
    }

    def "DefaultMemorySummarizer filters low importance steps"() {
        given:
        def steps = [
            Step.builder().stepId("1").action("a1").success(true).importance(0.1).build(),
            Step.builder().stepId("2").action("a2").success(true).importance(0.2).build(),
            Step.builder().stepId("3").action("a3").success(true).importance(0.5).build(),
        ]

        when:
        def summary = new DefaultMemorySummarizer().summarize(steps)

        then: "only importance >= 0.3 become facts"
        summary.facts().size() == 1
    }

    // ---------------------------------------------------------------
    // 7. VaultController 边缘场景
    // ---------------------------------------------------------------

    def "VaultController.memorize should assign higher importance to errors"() {
        given:
        vault = new VaultController()

        when:
        vault.memorize(Experience.builder()
                .sessionId("s1")
                .action("fail")
                .observation("尝试操作")
                .result("ERROR: connection timeout")
                .build())

        then:
        def steps = vault.episodicVault().getTrace("s1")
        steps.size() == 1
        steps[0].importance() > 0.5 // 0.5 base + 0.3 error = 0.8
        !steps[0].success()
    }

    def "VaultController.memorize should assign higher importance to long observations"() {
        given:
        vault = new VaultController()

        when:
        def longObs = "A" * 100
        vault.memorize(Experience.builder()
                .sessionId("s1")
                .action("analyze")
                .observation(longObs)
                .result("ok")
                .timestamp(System.currentTimeMillis())
                .build())

        then:
        def steps = vault.episodicVault().getTrace("s1")
        steps[0].importance() >= 0.7 // 0.5 base + 0.2 long obs = 0.7
    }

    def "VaultController.recall should reject null context"() {
        given:
        vault = new VaultController()

        when:
        vault.recall(null)

        then:
        thrown(NullPointerException)
    }

    def "VaultController.memorize should reject null experience"() {
        given:
        vault = new VaultController()

        when:
        vault.memorize(null)

        then:
        thrown(NullPointerException)
    }

    def "VaultController.finalizeSession should reject null sessionId"() {
        given:
        vault = new VaultController()

        when:
        vault.finalizeSession(null)

        then:
        thrown(NullPointerException)
    }

    def "VaultController recall without sessionId should fuse all vaults"() {
        given:
        vault = new VaultController()

        // 给 SemanticVault 加知识使路由不是空的
        vault.semanticVault().store(Knowledge.builder()
                .knowledgeId("k1").content("test knowledge").build())

        when: "a neutral query with no sessionId"
        def ctx = Context.builder()
                .query("hello world") // not episodic/semantic/procedural
                .build()
        def result = vault.recall(ctx)

        then: "should return a MemorySet (possibly empty)"
        result != null
    }

    def "VaultController all-args constructor should work"() {
        given:
        def episodic = new EpisodicVault()
        def semantic = new SemanticVault()
        def procedural = new ProceduralVault()
        def storage = new InMemoryStorageBackend()
        def summarizer = new DefaultMemorySummarizer()

        when:
        def vc = new VaultController(episodic, semantic, procedural, storage, summarizer)

        then:
        vc.episodicVault() is episodic
        vc.semanticVault() is semantic
        vc.proceduralVault() is procedural
        vc.storage() is storage
        vc.summarizer() is summarizer
    }

    def "VaultController.finalizeSession consolidates even with empty trace"() {
        given:
        vault = new VaultController()

        when:
        def summary = vault.finalizeSession("empty-session").get()

        then:
        summary.stepCount() == 0
        summary.facts().isEmpty()
        summary.successPatterns().isEmpty()
    }

    // ---------------------------------------------------------------
    // 8. InMemoryStorageBackend 边缘场景
    // ---------------------------------------------------------------

    def "InMemoryStorageBackend get nonexistent should return null"() {
        expect:
        new InMemoryStorageBackend().get("nokey") == null
    }

    def "InMemoryStorageBackend clear should remove all entries"() {
        given:
        def storage = new InMemoryStorageBackend()
        storage.put("k1", "v1".bytes)
        storage.put("k2", "v2".bytes)

        when:
        storage.clear()

        then:
        storage.size() == 0
        !storage.exists("k1")
    }

    def "InMemoryStorageBackend exists should work correctly"() {
        given:
        def storage = new InMemoryStorageBackend()

        expect:
        !storage.exists("k")

        when:
        storage.put("k", "v".bytes)

        then:
        storage.exists("k")
    }

    // ---------------------------------------------------------------
    // 9. 值对象 toString / 构建
    // ---------------------------------------------------------------

    def "Step builder should clamp importance to [0,1]"() {
        expect:
        Step.builder().stepId("s").importance(-0.5).build().importance() == 0.0
        Step.builder().stepId("s").importance(1.5).build().importance() == 1.0
    }

    def "Step toString should contain fields"() {
        given:
        def step = Step.builder()
                .stepId("my-step").action("test").success(true).importance(0.7)
                .build()

        expect:
        step.toString().contains("my-step")
        step.toString().contains("test")
        step.toString().contains("0.70")
    }

    def "Experience should auto-assign timestamp"() {
        when:
        def exp = Experience.builder()
                .sessionId("s").action("a").observation("o").build()

        then:
        exp.timestamp() > 0
    }

    def "Experience toString should contain sessionId and action"() {
        given:
        def exp = Experience.builder()
                .sessionId("sid").action("search").observation("query").result("result").build()

        expect:
        exp.toString().contains("sid")
        exp.toString().contains("search")
    }

    def "Knowledge toString should contain id and source"() {
        given:
        def k = Knowledge.builder()
                .knowledgeId("k42").source("docs").content("hello world").build()

        expect:
        k.toString().contains("k42")
        k.toString().contains("docs")
    }

    def "Knowledge equals by knowledgeId"() {
        given:
        def a = Knowledge.builder().knowledgeId("id1").content("a").build()
        def b = Knowledge.builder().knowledgeId("id1").content("b").build()
        def c = Knowledge.builder().knowledgeId("id2").content("a").build()

        expect:
        a == b
        a.hashCode() == b.hashCode()
        a != c
    }

    def "SOP toString should contain id, name, tool and version"() {
        given:
        def sop = SOP.builder()
                .sopId("sop-1").name("Build").pattern("p").procedure("proc")
                .toolName("gradle").version("2.0.0").build()

        expect:
        sop.toString().contains("sop-1")
        sop.toString().contains("Build")
        sop.toString().contains("gradle")
        sop.toString().contains("2.0.0")
    }

    def "SOP equals by sopId"() {
        given:
        def a = SOP.builder().sopId("id1").name("a").pattern("p").procedure("p").build()
        def b = SOP.builder().sopId("id1").name("b").pattern("p").procedure("p").build()
        def c = SOP.builder().sopId("id2").name("a").pattern("p").procedure("p").build()

        expect:
        a == b
        a.hashCode() == b.hashCode()
        a != c
    }

    def "SOP should auto-assign version if null"() {
        expect:
        SOP.builder().sopId("s").name("n").pattern("p").procedure("p").build().version() == "0.1.0"
    }

    def "Summary toString should contain sessionId and counts"() {
        given:
        def s = Summary.builder()
                .sessionId("x").facts(["f1"]).successPatterns(["p1"]).stepCount(10).build()

        expect:
        s.toString().contains("x")
        s.toString().contains("facts=1")
        s.toString().contains("patterns=1")
        s.toString().contains("steps=10")
    }

    def "Summary should be empty when no facts or patterns"() {
        expect:
        Summary.builder().sessionId("s").build().isEmpty()
    }

    def "MemoryRouter constructor should reject null vaults"() {
        when:
        new MemoryRouter(null, new SemanticVault(), new ProceduralVault())

        then:
        thrown(NullPointerException)
    }

    def "MemoryRouter.fuseAll should produce entries from all three vaults"() {
        given:
        def epi = new EpisodicVault()
        epi.appendStep("sid", Step.builder().stepId("s1").build())
        def sem = new SemanticVault()
        sem.store("c", Knowledge.builder().knowledgeId("k1").content("hello").build())
        def pro = new ProceduralVault()
        pro.register(SOP.builder().sopId("p1").name("P").pattern("pat").procedure("p").build())

        def router = new MemoryRouter(epi, sem, pro)

        when:
        def result = router.fuseAll(Context.builder().query("hello").sessionId("sid").build())

        then:
        result.size() > 0
    }
}
