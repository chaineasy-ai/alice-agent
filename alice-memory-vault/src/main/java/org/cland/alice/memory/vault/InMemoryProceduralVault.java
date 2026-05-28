package org.cland.alice.memory.vault;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import org.cland.alice.memory.agent.Context;
import org.cland.alice.memory.core.SOP;

/**
 * 程序记忆（Procedural Memory）Vault 的内存实现。
 *
 * <p>负责存储"最佳实践"和工具使用 Schema（SOP）， 支持模式匹配 / 语义路由，将上下文路由到匹配的 SOP。
 *
 * <p>对应设计文档：ProceduralVault / SopRegistry 角色。 物理载体建议：YAML / Git / Local Files（当前提供 InMemory 实现）。
 *
 * <p>支持版本控制——当文档中的工具 SOP 更新时， Agent 可通过 CI/CD 自动加载更新后的规范。
 */
public final class InMemoryProceduralVault implements ProceduralVault {

  /** 默认的最大匹配结果数 */
  private static final int DEFAULT_TOP_K = 5;

  private final Map<String, SOP> registry = new ConcurrentHashMap<>();
  private final List<SOP> sops = new CopyOnWriteArrayList<>();
  private final int topK;

  public InMemoryProceduralVault() {
    this(DEFAULT_TOP_K);
  }

  public InMemoryProceduralVault(int topK) {
    this.topK = topK;
  }

  // ---------------------------------------------------------------
  // 写操作
  // ---------------------------------------------------------------

  /** 注册 / 更新一个 SOP。 如果 sopId 已存在，则更新之。 */
  public void register(SOP sop) {
    Objects.requireNonNull(sop, "sop must not be null");
    if (registry.containsKey(sop.sopId())) {
      // 更新已存在的 SOP
      int index = indexOf(sop.sopId());
      if (index >= 0) {
        sops.set(index, sop);
      } else {
        sops.add(sop);
      }
    } else {
      sops.add(sop);
    }
    registry.put(sop.sopId(), sop);
  }

  /** 批量注册 SOP。 */
  public void registerAll(List<SOP> sopList) {
    sopList.forEach(this::register);
  }

  // ---------------------------------------------------------------
  // 读操作
  // ---------------------------------------------------------------

  /**
   * 根据上下文匹配最相关的 SOP。 匹配策略：先按 toolName 精确匹配，再按 pattern 关键词匹配。
   *
   * @param ctx 查询上下文
   * @return 按匹配度降序排列的 SOP 列表
   */
  public List<SOP> match(Context ctx) {
    String query = ctx.query().toLowerCase();

    // 1. 精确工具名匹配（最高优先级）
    List<ScoredSOP> scored = new ArrayList<>();
    for (SOP sop : sops) {
      double score = matchScore(sop, query, ctx);
      if (score > 0) {
        scored.add(new ScoredSOP(sop, score));
      }
    }

    // 2. 按得分降序排列
    scored.sort(Comparator.<ScoredSOP>comparingDouble(ScoredSOP::score).reversed());
    return scored.stream().limit(topK).map(ScoredSOP::sop).collect(Collectors.toUnmodifiableList());
  }

  /** 根据工具名精确查找 SOP。 */
  public List<SOP> findByTool(String toolName) {
    return sops.stream()
        .filter(s -> toolName.equals(s.toolName()))
        .collect(Collectors.toUnmodifiableList());
  }

  /** 根据 SOP ID 获取 SOP。 */
  public SOP getById(String sopId) {
    return registry.get(sopId);
  }

  /** 获取所有已注册的 SOP。 */
  public List<SOP> getAll() {
    return List.copyOf(sops);
  }

  /** 获取 SOP 数量。 */
  public int count() {
    return sops.size();
  }

  // ---------------------------------------------------------------
  // 删除
  // ---------------------------------------------------------------

  /** 根据 SOP ID 删除一个 SOP。 */
  public boolean remove(String sopId) {
    registry.remove(sopId);
    return sops.removeIf(s -> sopId.equals(s.sopId()));
  }

  /** 清除所有 SOP。 */
  public void clearAll() {
    registry.clear();
    sops.clear();
  }

  // ---------------------------------------------------------------
  // 匹配逻辑
  // ---------------------------------------------------------------

  /** 计算 SOP 与查询上下文的匹配得分。 得分范围 0.0 ~ 1.0。 */
  private double matchScore(SOP sop, String query, Context ctx) {
    double score = 0.0;

    // 精确工具名匹配 -> 0.9
    if (sop.toolName() != null && query.contains(sop.toolName().toLowerCase())) {
      score = Math.max(score, 0.9);
    }

    // pattern 精确匹配 -> 0.8
    if (query.contains(sop.pattern().toLowerCase())) {
      score = Math.max(score, 0.8);
    }

    // 关键词重叠匹配 -> 0.3 ~ 0.7
    String[] keywords = sop.pattern().toLowerCase().split("[\\s,，、]+");
    int matchCount = 0;
    for (String kw : keywords) {
      if (kw.length() > 1 && query.contains(kw)) {
        matchCount++;
      }
    }
    if (keywords.length > 0) {
      double kwScore = (double) matchCount / keywords.length * 0.7;
      score = Math.max(score, kwScore);
    }

    // SOP 名称匹配 -> 0.5
    if (query.contains(sop.name().toLowerCase())) {
      score = Math.max(score, 0.5);
    }

    return score;
  }

  /** 根据 sopId 查找在列表中的索引。 */
  private int indexOf(String sopId) {
    for (int i = 0; i < sops.size(); i++) {
      if (sopId.equals(sops.get(i).sopId())) {
        return i;
      }
    }
    return -1;
  }

  // ---------------------------------------------------------------

  private record ScoredSOP(SOP sop, double score) {}

  @Override
  public String toString() {
    return "InMemoryProceduralVault{sops=%d}".formatted(sops.size());
  }
}
