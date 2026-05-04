package org.cland.alice.memory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 语义记忆（Semantic Memory）Vault。
 * <p>
 * 负责存储结构化/非结构化知识（如项目文档、技术手册），
 * 支持通过向量相似度（HNSW）进行语义检索。
 * <p>
 * 对应设计文档：SemanticVault / VectorStore + EmbeddingModel 角色。
 * 物理载体建议：Qdrant / Milvus（当前提供内存实现，含简易 TF-IDF 检索）。
 * <p>
 * 支持 Collection 隔离——Project-C-Land 的私有 API 文档
 * 不应在处理通用技术咨询时被误检索。
 */
public final class SemanticVault {

    /** 默认的最大检索结果数 */
    private static final int DEFAULT_TOP_K = 10;

    /** 相似度阈值，低于此值的结果将被过滤 */
    private static final double DEFAULT_SIMILARITY_THRESHOLD = 0.3;

    private final Map<String, List<Knowledge>> collections = new ConcurrentHashMap<>();
    private final int topK;
    private final double similarityThreshold;

    public SemanticVault() {
        this(DEFAULT_TOP_K, DEFAULT_SIMILARITY_THRESHOLD);
    }

    public SemanticVault(int topK, double similarityThreshold) {
        this.topK = topK;
        this.similarityThreshold = similarityThreshold;
    }

    // ---------------------------------------------------------------
    // 写操作
    // ---------------------------------------------------------------

    /**
     * 向指定 collection 存入一条知识。
     */
    public void store(String collection, Knowledge knowledge) {
        Objects.requireNonNull(collection, "collection must not be null");
        Objects.requireNonNull(knowledge, "knowledge must not be null");
        collections.computeIfAbsent(collection, k -> new CopyOnWriteArrayList<>()).add(knowledge);
    }

    /**
     * 向知识条目自带的 collection（{@link Knowledge#collection()}）存入一条知识。
     * 如果 collection 为 null 或空，则存入默认 collection（"_default"）。
     */
    public void store(Knowledge knowledge) {
        String coll = knowledge.collection();
        if (coll == null || coll.isBlank()) {
            coll = "_default";
        }
        store(coll, knowledge);
    }

    /**
     * 批量存入知识。
     */
    public void storeAll(String collection, List<Knowledge> knowledgeList) {
        Objects.requireNonNull(collection);
        Objects.requireNonNull(knowledgeList);
        collections.computeIfAbsent(collection, k -> new CopyOnWriteArrayList<>())
                .addAll(knowledgeList);
    }

    // ---------------------------------------------------------------
    // 读操作
    // ---------------------------------------------------------------

    /**
     * 在指定 collection 中检索与查询最相似的 {@code topK} 条知识。
     *
     * @param collection collection 名称
     * @param query      查询文本
     * @return 按相似度降序排列的知识列表
     */
    public List<Knowledge> search(String collection, String query) {
        List<Knowledge> all = collections.get(collection);
        if (all == null || all.isEmpty()) return List.of();

        // 简易向量相似度检索（基于 TF-IDF 风格的文本匹配）
        return all.stream()
                .map(k -> new ScoredKnowledge(k, computeSimilarity(k.content(), query)))
                .filter(sk -> sk.score() >= similarityThreshold)
                .sorted(Comparator.<ScoredKnowledge>comparingDouble(ScoredKnowledge::score).reversed())
                .limit(topK)
                .map(ScoredKnowledge::knowledge)
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * 在所有 collection 中检索（跨 collection 搜索）。
     */
    public List<Knowledge> searchAll(String query) {
        List<ScoredKnowledge> scored = new ArrayList<>();
        for (var entry : collections.entrySet()) {
            for (Knowledge k : entry.getValue()) {
                double score = computeSimilarity(k.content(), query);
                if (score >= similarityThreshold) {
                    scored.add(new ScoredKnowledge(k, score));
                }
            }
        }
        scored.sort(Comparator.<ScoredKnowledge>comparingDouble(ScoredKnowledge::score).reversed());
        return scored.stream()
                .limit(topK)
                .map(ScoredKnowledge::knowledge)
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * 获取指定 collection 中的所有知识。
     */
    public List<Knowledge> getAll(String collection) {
        List<Knowledge> all = collections.get(collection);
        return all != null ? List.copyOf(all) : List.of();
    }

    /**
     * 获取所有 collection 名称。
     */
    public List<String> getCollections() {
        return List.copyOf(collections.keySet());
    }

    /**
     * 获取指定 collection 中的知识数量。
     */
    public int count(String collection) {
        List<Knowledge> all = collections.get(collection);
        return all != null ? all.size() : 0;
    }

    // ---------------------------------------------------------------
    // 删除
    // ---------------------------------------------------------------

    /**
     * 删除指定 collection 中的一条知识。
     */
    public boolean remove(String collection, String knowledgeId) {
        List<Knowledge> all = collections.get(collection);
        if (all == null) return false;
        return all.removeIf(k -> knowledgeId.equals(k.knowledgeId()));
    }

    /**
     * 删除整个 collection。
     */
    public void removeCollection(String collection) {
        collections.remove(collection);
    }

    /**
     * 清除所有知识。
     */
    public void clearAll() {
        collections.clear();
    }

    // ---------------------------------------------------------------
    // 简易向量相似度计算（基于词频 + IDF 风格）
    // ---------------------------------------------------------------

    /**
     * 计算两段文本的相似度（0.0 ~ 1.0）。
     * 采用简单的词袋 Jaccard 相似度 + 长度归一化。
     * 真实生产环境应替换为 EmbeddingModel 的向量点积/余弦相似度。
     */
    private double computeSimilarity(String text1, String text2) {
        if (text1 == null || text2 == null) return 0.0;
        if (text1.isEmpty() && text2.isEmpty()) return 1.0;
        if (text1.isEmpty() || text2.isEmpty()) return 0.0;

        // 分词（按非字母数字字符分割）
        var tokens1 = tokenize(text1);
        var tokens2 = tokenize(text2);

        // Jaccard 相似度
        var intersection = new java.util.HashSet<>(tokens1);
        intersection.retainAll(tokens2);

        var union = new java.util.HashSet<>(tokens1);
        union.addAll(tokens2);

        if (union.isEmpty()) return 0.0;

        double jaccard = (double) intersection.size() / union.size();

        // 长度归一化惩罚：长度悬殊太大的文本即使有重叠词也降低相似度
        double lenRatio = Math.min(text1.length(), text2.length())
                / (double) Math.max(text1.length(), text2.length());

        return jaccard * Math.sqrt(lenRatio);
    }

    private java.util.Set<String> tokenize(String text) {
        var tokens = new java.util.HashSet<String>();
        var sb = new StringBuilder();
        for (char c : text.toLowerCase().toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                sb.append(c);
            } else if (sb.length() > 0) {
                if (sb.length() > 1) { // 忽略单字符
                    tokens.add(sb.toString());
                }
                sb.setLength(0);
            }
        }
        if (sb.length() > 1) {
            tokens.add(sb.toString());
        }
        return tokens;
    }

    // ---------------------------------------------------------------
    // 内部记录
    // ---------------------------------------------------------------

    private record ScoredKnowledge(Knowledge knowledge, double score) {}

    @Override
    public String toString() {
        int total = collections.values().stream().mapToInt(List::size).sum();
        return "SemanticVault{collections=%d, totalKnowledge=%d}"
                .formatted(collections.size(), total);
    }
}
