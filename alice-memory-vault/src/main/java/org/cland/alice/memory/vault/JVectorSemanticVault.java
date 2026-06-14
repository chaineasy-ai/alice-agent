/*
 * JVectorSemanticVault — 基于 JVector 的语义记忆 Vault
 *
 * 使用 DataStax JVector 4.x 提供的纯 Java 嵌入式向量索引。
 * 每个 Collection 对应一个独立的 JVector 图索引。
 * Knowledge 内容通过哈希投影转为固定维度 (32) 浮点向量。
 */
package org.cland.alice.memory.vault;

import io.github.jbellis.jvector.graph.GraphIndexBuilder;
import io.github.jbellis.jvector.graph.GraphSearcher;
import io.github.jbellis.jvector.graph.OnHeapGraphIndex;
import io.github.jbellis.jvector.graph.RandomAccessVectorValues;
import io.github.jbellis.jvector.graph.SearchResult;
import io.github.jbellis.jvector.util.Bits;
import io.github.jbellis.jvector.vector.VectorSimilarityFunction;
import io.github.jbellis.jvector.vector.types.VectorFloat;
import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.cland.alice.memory.core.Knowledge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 基于 JVector 的语义记忆 Vault 实现。
 *
 * <p>每个 Collection 维护一个独立的 JVector 图索引。 Knowledge 的内容通过词袋哈希投影到固定维度（{@code VECTOR_DIM}=32）的浮点向量。
 *
 * <p>搜索时使用 COSINE 相似度进行 ANN 近似最近邻搜索。
 */
public final class JVectorSemanticVault implements SemanticVault, Closeable {

  private static final Logger log = LoggerFactory.getLogger(JVectorSemanticVault.class);

  /** 向量维度 */
  /** 向量维度（128 维：平衡检索精度与性能） */
  public static final int VECTOR_DIM = 128;

  /** 默认最大检索结果数 */
  public static final int DEFAULT_TOP_K = 10;

  /** 相似度阈值 */
  public static final double DEFAULT_SIMILARITY_THRESHOLD = 0.3;

  /** HNSW M 参数 */
  public static final int HNSW_M = 16;

  /** efConstruction 参数 */
  public static final int HNSW_EF_CONSTRUCTION = 100;

  private final int topK;
  private final double similarityThreshold;
  private final AtomicInteger globalNodeId = new AtomicInteger(1);
  private final ConcurrentMap<String, CollectionIndex> collections = new ConcurrentHashMap<>();

  public JVectorSemanticVault() {
    this(DEFAULT_TOP_K, DEFAULT_SIMILARITY_THRESHOLD);
  }

  public JVectorSemanticVault(int topK, double similarityThreshold) {
    this.topK = topK;
    this.similarityThreshold = similarityThreshold;
  }

  // ============================================================
  // 写操作
  // ============================================================

  @Override
  public void store(String collection, Knowledge knowledge) {
    Objects.requireNonNull(collection, "collection must not be null");
    Objects.requireNonNull(knowledge, "knowledge must not be null");
    var ci = collections.computeIfAbsent(collection, k -> new CollectionIndex(collection));
    ci.add(globalNodeId.getAndIncrement(), knowledge);
  }

  @Override
  public void store(Knowledge knowledge) {
    String coll = knowledge.collection();
    if (coll == null || coll.isBlank()) coll = "_default";
    store(coll, knowledge);
  }

  @Override
  public void storeAll(String collection, List<Knowledge> knowledgeList) {
    Objects.requireNonNull(collection, "collection must not be null");
    Objects.requireNonNull(knowledgeList, "knowledgeList must not be null");
    for (Knowledge k : knowledgeList) store(collection, k);
  }

  // ============================================================
  // 读操作
  // ============================================================

  @Override
  public List<Knowledge> search(String collection, String query) {
    var ci = collections.get(collection);
    if (ci == null || ci.isEmpty()) return List.of();
    return ci.search(textToVector(query), topK, similarityThreshold);
  }

  @Override
  public List<Knowledge> searchAll(String query) {
    if (collections.isEmpty()) return List.of();
    float[] qv = textToVector(query);
    List<Knowledge> all = new ArrayList<>();
    for (var ci : collections.values()) {
      if (!ci.isEmpty()) {
        all.addAll(ci.search(qv, topK, similarityThreshold));
      }
    }
    return all.stream().limit(topK).collect(Collectors.toList());
  }

  @Override
  public List<Knowledge> getAll(String collection) {
    var ci = collections.get(collection);
    return ci != null ? ci.getAll() : List.of();
  }

  @Override
  public List<String> getCollections() {
    return List.copyOf(collections.keySet());
  }

  @Override
  public int count(String collection) {
    var ci = collections.get(collection);
    return ci != null ? ci.size() : 0;
  }

  // ============================================================
  // 删除操作
  // ============================================================

  @Override
  public boolean remove(String collection, String knowledgeId) {
    var ci = collections.get(collection);
    return ci != null && ci.remove(knowledgeId);
  }

  @Override
  public void removeCollection(String collection) {
    var ci = collections.remove(collection);
    if (ci != null) ci.close();
  }

  @Override
  public void clearAll() {
    collections.values().forEach(CollectionIndex::close);
    collections.clear();
  }

  @Override
  public void close() {
    clearAll();
  }

  // ============================================================
  // 文本向量化
  // ============================================================

  /** 将文本转为固定维度的归一化浮点向量（哈希投影）。 */
  static float[] textToVector(String text) {
    float[] vec = new float[VECTOR_DIM];
    if (text == null || text.isEmpty()) return vec;

    String[] tokens = tokenize(text);
    if (tokens.length == 0) return vec;

    for (String token : tokens) {
      int h = token.hashCode();
      int idx1 = Math.floorMod(h, VECTOR_DIM);
      int idx2 = Math.floorMod(h * 31 + 17, VECTOR_DIM);
      float scale = (float) (1.0 / Math.sqrt(tokens.length));
      vec[idx1] += scale * ((h & 1) == 0 ? 1 : -1);
      vec[idx2] -= scale * (((h * 7 + 3) & 1) == 0 ? 1 : -1);
    }

    float norm = 0f;
    for (float v : vec) norm += v * v;
    norm = (float) Math.sqrt(norm);
    if (norm > 1e-8f) {
      for (int i = 0; i < VECTOR_DIM; i++) vec[i] /= norm;
    }
    return vec;
  }

  private static String[] tokenize(String text) {
    return text.toLowerCase().replaceAll("[^a-zA-Z0-9\u4e00-\u9fff]", " ").trim().split("\\s+");
  }

  // ============================================================
  // float[] → VectorFloat 包装
  // ============================================================

  /** 将 float[] 包装为 JVector 的 VectorFloat（通过 JVector 自己的 provider）。 */
  static VectorFloat<float[]> wrap(float[] data) {
    return (VectorFloat<float[]>) VECTOR_TYPE_SUPPORT.createFloatVector(data);
  }

  private static final io.github.jbellis.jvector.vector.types.VectorTypeSupport
      VECTOR_TYPE_SUPPORT =
          io.github.jbellis.jvector.vector.VectorizationProvider.getInstance()
              .getVectorTypeSupport();

  // ============================================================
  // CollectionIndex (基于 List 索引)
  // ============================================================

  /** 单个 Collection 的索引。节点 ID 从 0 开始连续分配。 */
  static class CollectionIndex implements Closeable {
    final String name;
    final List<Knowledge> knowledgeList = new CopyOnWriteArrayList<>();
    final Map<Integer, Knowledge> nodeToKnowledge = new ConcurrentHashMap<>();

    // List 索引 = local nodeId
    final List<VectorFloat<float[]>> vectorList = new ArrayList<>();

    final RandomAccessVectorValues ravv;
    final GraphIndexBuilder builder;
    OnHeapGraphIndex index;

    CollectionIndex(String name) {
      this.name = name;
      this.ravv = new ListBackedVectorValues(vectorList);
      this.builder =
          new GraphIndexBuilder(
              ravv,
              VectorSimilarityFunction.COSINE,
              HNSW_M,
              HNSW_EF_CONSTRUCTION,
              1.2f,
              1.0f,
              false);
    }

    synchronized void add(int nodeId, Knowledge knowledge) {
      float[] vec = textToVector(knowledge.content());
      int localId = vectorList.size(); // 0-based
      knowledgeList.add(knowledge);
      nodeToKnowledge.put(localId, knowledge);
      vectorList.add(wrap(vec));

      try {
        if (index == null) {
          index = builder.build(ravv);
        } else {
          builder.addGraphNode(localId, ravv);
        }
      } catch (Exception e) {
        log.warn("JVector addGraphNode failed (localId={}): {}", localId, e.getMessage());
      }
    }

    synchronized List<Knowledge> search(float[] qv, int k, double threshold) {
      if (index == null || index.size() == 0) return List.of();
      try {
        VectorFloat<float[]> query = wrap(qv);
        SearchResult result =
            GraphSearcher.search(query, k, ravv, VectorSimilarityFunction.COSINE, index, Bits.ALL);

        List<Knowledge> results = new ArrayList<>();
        for (var ns : result.getNodes()) {
          Knowledge kn = nodeToKnowledge.get(ns.node);
          if (kn != null && ns.score >= (float) threshold) {
            results.add(kn);
          }
        }
        return results;
      } catch (Exception e) {
        log.warn("JVector search failed: {}", e.getMessage());
        return List.of();
      }
    }

    synchronized boolean remove(String knowledgeId) {
      int localId = findNodeId(knowledgeId);
      if (localId < 0) return false;
      builder.markNodeDeleted(localId);
      knowledgeList.removeIf(k -> k.knowledgeId().equals(knowledgeId));
      nodeToKnowledge.remove(localId);
      return true;
    }

    private int findNodeId(String knowledgeId) {
      for (var entry : nodeToKnowledge.entrySet()) {
        if (entry.getValue().knowledgeId().equals(knowledgeId)) return entry.getKey();
      }
      return -1;
    }

    List<Knowledge> getAll() {
      return List.copyOf(knowledgeList);
    }

    int size() {
      return knowledgeList.size();
    }

    boolean isEmpty() {
      return knowledgeList.isEmpty();
    }

    @Override
    public void close() {
      knowledgeList.clear();
      nodeToKnowledge.clear();
      vectorList.clear();
      index = null;
    }
  }

  // ============================================================
  // ListBackedVectorValues
  // ============================================================

  /** 基于 List<VectorFloat<float[]>> 的 RandomAccessVectorValues。节点 ID = list index。 */
  static class ListBackedVectorValues implements RandomAccessVectorValues {
    final List<VectorFloat<float[]>> data;

    ListBackedVectorValues(List<VectorFloat<float[]>> data) {
      this.data = data;
    }

    @Override
    public int size() {
      return data.size();
    }

    @Override
    public int dimension() {
      return VECTOR_DIM;
    }

    @Override
    public VectorFloat<?> getVector(int nodeId) {
      if (nodeId < 0 || nodeId >= data.size()) return null;
      return data.get(nodeId);
    }

    @Override
    public boolean isValueShared() {
      return false;
    }

    @Override
    public RandomAccessVectorValues copy() {
      return this;
    }
  }

  @Override
  public String toString() {
    int total = collections.values().stream().mapToInt(CollectionIndex::size).sum();
    return "JVectorSemanticVault{collections=%d, totalKnowledge=%d}"
        .formatted(collections.size(), total);
  }
}
