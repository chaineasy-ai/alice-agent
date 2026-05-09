package org.cland.alice.memory;

import java.util.List;
import java.util.Objects;

/**
 * 记忆路由器——分析查询上下文，将请求分发到合适的 Vault。
 *
 * <p>对应设计文档中状态机 "Routing to Episodic / Semantic / Procedural" 的角色。
 *
 * <p>路由逻辑：
 *
 * <ol>
 *   <li>如果上下文涉及"刚才/之前做了什么" → 路由到 EpisodicVault
 *   <li>如果上下文涉及"这是什么技术/概念" → 路由到 SemanticVault
 *   <li>如果上下文涉及"如何执行这个工具" → 路由到 ProceduralVault
 *   <li>如果无明确倾向 → 三种 vault 都查询，结果融合
 * </ol>
 */
public final class MemoryRouter {

  private final EpisodicVault episodicVault;
  private final SemanticVault semanticVault;
  private final ProceduralVault proceduralVault;

  public MemoryRouter(
      EpisodicVault episodicVault, SemanticVault semanticVault, ProceduralVault proceduralVault) {
    this.episodicVault = Objects.requireNonNull(episodicVault, "episodicVault must not be null");
    this.semanticVault = Objects.requireNonNull(semanticVault, "semanticVault must not be null");
    this.proceduralVault =
        Objects.requireNonNull(proceduralVault, "proceduralVault must not be null");
  }

  /** 根据查询上下文路由到合适的 vault(s) 并返回融合的记忆集合。 */
  public MemorySet route(Context ctx) {
    Objects.requireNonNull(ctx, "ctx must not be null");

    // 统计路由倾向
    boolean needsEpisodic = ctx.isEpisodicQuery() || ctx.sessionId() != null;
    boolean needsSemantic = ctx.isSemanticQuery() || ctx.isEpisodicQuery(); // episodic 也常伴随语义
    boolean needsProcedural = ctx.isProceduralQuery();

    // 如果没有任何明确倾向，执行全检索（知识融合）
    if (!needsEpisodic && !needsSemantic && !needsProcedural) {
      return fuseAll(ctx);
    }

    MemorySet.Builder builder = MemorySet.builder();

    if (needsEpisodic) {
      queryEpisodic(ctx, builder);
    }
    if (needsSemantic) {
      querySemantic(ctx, builder);
    }
    if (needsProcedural) {
      queryProcedural(ctx, builder);
    }

    return builder.build();
  }

  /** 全检索模式——三种 vault 都查询，结果融合。 */
  public MemorySet fuseAll(Context ctx) {
    MemorySet.Builder builder = MemorySet.builder();
    queryEpisodic(ctx, builder);
    querySemantic(ctx, builder);
    queryProcedural(ctx, builder);
    return builder.build();
  }

  // ---------------------------------------------------------------
  // 各 vault 查询
  // ---------------------------------------------------------------

  private void queryEpisodic(Context ctx, MemorySet.Builder builder) {
    String sessionId = ctx.sessionId();
    if (sessionId == null) return;

    List<Step> recent = episodicVault.getRecentSteps(sessionId, 10);
    for (Step step : recent) {
      String content = "[%s] %s → %s".formatted(step.action(), step.input(), step.output());
      builder.addEpisodic(sessionId, content, step.timestamp(), step.importance());
    }
  }

  private void querySemantic(Context ctx, MemorySet.Builder builder) {
    String query = ctx.query();
    String collection = ctx.metadata("collection");

    List<Knowledge> results;
    if (collection != null) {
      results = semanticVault.search(collection, query);
    } else {
      results = semanticVault.searchAll(query);
    }

    for (Knowledge k : results) {
      builder.addSemantic(k.knowledgeId(), k.content(), 0.9); // score from search
    }
  }

  private void queryProcedural(Context ctx, MemorySet.Builder builder) {
    List<SOP> matches = proceduralVault.match(ctx);
    for (SOP sop : matches) {
      builder.addProcedural(sop.sopId(), sop.pattern(), sop.procedure(), 0.9);
    }
  }

  // ---------------------------------------------------------------
  // 组件访问（供 VaultController 使用）
  // ---------------------------------------------------------------

  public EpisodicVault episodicVault() {
    return episodicVault;
  }

  public SemanticVault semanticVault() {
    return semanticVault;
  }

  public ProceduralVault proceduralVault() {
    return proceduralVault;
  }

  @Override
  public String toString() {
    return "MemoryRouter{episodic=%d sessions, semantic=%d collections, procedural=%d sops}"
        .formatted(
            episodicVault.sessionCount(),
            semanticVault.getCollections().size(),
            proceduralVault.count());
  }
}
