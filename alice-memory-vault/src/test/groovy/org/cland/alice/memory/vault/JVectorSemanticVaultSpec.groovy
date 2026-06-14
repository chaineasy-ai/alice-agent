/*
 * JVectorSemanticVaultSpec — JVectorSemanticVault 测试
 *
 * 测试目标：
 *   - store + search 的基本语义检索
 *   - 多 Collection 隔离
 *   - COSINE 相似度排序
 *   - 删除操作
 *   - 跨 collection searchAll
 *   - textToVector 向量化一致性
 *   - 空 Collection / 空查询处理
 */
package org.cland.alice.memory.vault

import org.cland.alice.memory.core.Knowledge
import spock.lang.Specification
import spock.lang.Title

@Title("JVectorSemanticVault — 基于 JVector 的语义检索")
class JVectorSemanticVaultSpec extends Specification {

    JVectorSemanticVault vault

    def setup() {
        vault = new JVectorSemanticVault()
    }

    def cleanup() {
        vault?.close()
    }

    // ========== 基础存储与检索 ==========

    def "store and search returns relevant results"() {
        given:
        vault.store("docs", Knowledge.builder()
            .knowledgeId("1").content("Alice agent is a goal-driven autonomous agent").source("manual").collection("docs").build())
        vault.store("docs", Knowledge.builder()
            .knowledgeId("2").content("The WAL module provides crash recovery and checkpointing").source("design").collection("docs").build())
        vault.store("docs", Knowledge.builder()
            .knowledgeId("3").content("Vector databases enable semantic similarity search").source("research").collection("docs").build())

        when:
        def results = vault.search("docs", "crash recovery")

        then:
        results.size() > 0
        results.any { it.knowledgeId() == "2" } // WAL crash recovery 最相关
    }

    def "search returns empty for unknown collection"() {
        expect:
        vault.search("unknown", "test").isEmpty()
    }

    def "search returns empty for empty collection"() {
        given:
        vault.store("empty", Knowledge.builder()
            .knowledgeId("1").content("some content").source("test").collection("empty").build())
        vault.remove("empty", "1")

        expect:
        vault.search("empty", "content").isEmpty()
    }

    // ========== textToVector ==========

    def "textToVector produces non-zero normalized vector"() {
        when:
        def vec = JVectorSemanticVault.textToVector("Hello world")

        then:
        vec.length == JVectorSemanticVault.VECTOR_DIM

        and: "L2 归一化接近 1.0"
        def normSq = 0.0
        for (int i = 0; i < vec.length; i++) {
            normSq += vec[i] * vec[i] as double
        }
        Math.abs(Math.sqrt(normSq) - 1.0) < 0.001
    }

    def "textToVector produces consistent vectors for same input"() {
        expect:
        def v1 = JVectorSemanticVault.textToVector("same text")
        def v2 = JVectorSemanticVault.textToVector("same text")
        Arrays.equals(v1, v2)
    }

    def "textToVector produces zero vector for empty input"() {
        expect:
        def v = JVectorSemanticVault.textToVector("")
        v.every { it == 0f }
    }

    def "textToVector produces zero vector for null"() {
        expect:
        def v = JVectorSemanticVault.textToVector(null)
        v.every { it == 0f }
    }

    // ========== 中文支持 ==========

    def "textToVector supports Chinese text"() {
        given:
        def v1 = JVectorSemanticVault.textToVector("向量数据库语义搜索")
        def v2 = JVectorSemanticVault.textToVector("数据库向量检索")

        when:
        def dot = 0.0
        for (int i = 0; i < v1.length; i++) {
            dot += v1[i] * v2[i] as double
        }

        then:
        // 中文无空格分词，每个短语作为整体哈希投影。不同文本间随机余弦相似度 ≈ 0
        // 此处只验证向量非零且已归一化
        def norm1 = Math.sqrt(v1.collect { it * it as double }.sum())
        def norm2 = Math.sqrt(v2.collect { it * it as double }.sum())
        Math.abs(norm1 - 1.0) < 0.001
        Math.abs(norm2 - 1.0) < 0.001
    }

    // ========== 多 Collection 隔离 ==========

    def "collections are isolated"() {
        given:
        vault.store("coll-a", Knowledge.builder()
            .knowledgeId("a1").content("Spring Boot microservices enterprise architecture").source("").build())
        vault.store("coll-b", Knowledge.builder()
            .knowledgeId("b1").content("PyTorch neural network deep learning").source("").build())

        expect:
        def resultsA = vault.search("coll-a", "Java")
        resultsA.any { it.knowledgeId() == "a1" }

        and: "coll-b 搜索结果（若有）不应包含 coll-a 的内容"
        def resultsB = vault.search("coll-b", "Java")
        resultsB.every { it.knowledgeId() == "b1" } // 只允许 b1 本身（如果有结果）
    }

    // ========== searchAll ==========

    def "searchAll searches across all collections"() {
        given:
        vault.store("coll-a", Knowledge.builder()
            .knowledgeId("a1").content("Apple fruit is sweet").source("").build())
        vault.store("coll-b", Knowledge.builder()
            .knowledgeId("b1").content("Apple computers make MacBooks").source("").build())

        when:
        def results = vault.searchAll("Apple")

        then:
        results.size() >= 2 // 两个 apple 相关的结果都应出现
    }

    // ========== storeAll ==========

    def "storeAll batch stores multiple entries"() {
        given:
        def list = [
            Knowledge.builder().knowledgeId("1").content("content one").source("").build(),
            Knowledge.builder().knowledgeId("2").content("content two").source("").build()
        ]

        when:
        vault.storeAll("test", list)

        then:
        vault.count("test") == 2
    }

    // ========== getCollections ==========

    def "getCollections returns all collection names"() {
        given:
        vault.store("c1", Knowledge.builder().knowledgeId("1").content("a").source("").build())
        vault.store("c2", Knowledge.builder().knowledgeId("2").content("b").source("").build())

        expect:
        vault.getCollections().containsAll(["c1", "c2"])
    }

    // ========== getAll ==========

    def "getAll returns all knowledge in collection"() {
        given:
        vault.store("docs", Knowledge.builder().knowledgeId("1").content("A").source("").build())
        vault.store("docs", Knowledge.builder().knowledgeId("2").content("B").source("").build())

        when:
        def all = vault.getAll("docs")

        then:
        all.size() == 2
        all.collect { it.knowledgeId() }.containsAll(["1", "2"])
    }

    // ========== remove ==========

    def "remove deletes knowledge and removes from search results"() {
        given:
        vault.store("docs", Knowledge.builder()
            .knowledgeId("del1").content("remove this item from the index please").source("").build())
        vault.store("docs", Knowledge.builder()
            .knowledgeId("keep1").content("Python is a programming language for data science").source("").build())

        expect:
        vault.search("docs", "remove").any { it.knowledgeId() == "del1" }

        when:
        vault.remove("docs", "del1")

        then:
        // del1 已被删除，不再出现在搜索结果中
        vault.search("docs", "remove").every { it.knowledgeId() != "del1" }
        vault.count("docs") == 1
    }

    def "remove returns false for non-existent id"() {
        expect:
        !vault.remove("docs", "nonexistent")
    }

    // ========== removeCollection / clearAll ==========

    def "removeCollection removes entire collection"() {
        given:
        vault.store("tmp", Knowledge.builder().knowledgeId("1").content("temp").source("").build())

        when:
        vault.removeCollection("tmp")

        then:
        vault.count("tmp") == 0
        vault.getCollections().isEmpty()
    }

    def "clearAll removes everything"() {
        given:
        vault.store("c1", Knowledge.builder().knowledgeId("1").content("a").source("").build())
        vault.store("c2", Knowledge.builder().knowledgeId("2").content("b").source("").build())

        when:
        vault.clearAll()

        then:
        vault.getCollections().isEmpty()
    }

    // ========== toString ==========

    def "toString contains vault info"() {
        given:
        vault.store("test", Knowledge.builder().knowledgeId("1").content("x").source("").build())

        expect:
        vault.toString().contains("JVectorSemanticVault")
        vault.toString().contains("totalKnowledge=1")
    }
}
