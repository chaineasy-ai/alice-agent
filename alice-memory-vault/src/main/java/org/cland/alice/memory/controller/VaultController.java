package org.cland.alice.memory.controller;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.cland.alice.memory.agent.Context;
import org.cland.alice.memory.core.Experience;
import org.cland.alice.memory.core.Knowledge;
import org.cland.alice.memory.core.MemorySet;
import org.cland.alice.memory.core.SOP;
import org.cland.alice.memory.core.Step;
import org.cland.alice.memory.core.Summary;
import org.cland.alice.memory.router.DefaultMemorySummarizer;
import org.cland.alice.memory.router.MemoryRouter;
import org.cland.alice.memory.router.MemorySummarizer;
import org.cland.alice.memory.storage.InMemoryStorageBackend;
import org.cland.alice.memory.storage.StorageBackend;
import org.cland.alice.memory.vault.EpisodicVault;
import org.cland.alice.memory.vault.ProceduralVault;
import org.cland.alice.memory.vault.SemanticVault;

/**
 * 记忆控制器——三段式记忆系统的统一入口。
 *
 * <p>对应设计文档中 VaultController 的角色，以组合模式统一管理 {@link EpisodicVault}、{@link SemanticVault} 和 {@link
 * ProceduralVault}。
 *
 * <p>核心职责：
 *
 * <ul>
 *   <li><b>recall(Context)</b>：根据查询上下文检索相关记忆
 *   <li><b>memorize(Experience)</b>：摄入一次交互经验
 *   <li><b>finalizeSession(sessionId)</b>：会话结束时触发记忆合并（Consolidation）
 * </ul>
 */
public final class VaultController {

  private final MemoryRouter router;
  private final EpisodicVault episodicVault;
  private final SemanticVault semanticVault;
  private final ProceduralVault proceduralVault;
  private final StorageBackend storage;
  private final MemorySummarizer summarizer;
  private final Executor consolidationExecutor;

  // ---------------------------------------------------------------
  // 构造
  // ---------------------------------------------------------------

  public VaultController() {
    this.episodicVault = new EpisodicVault();
    this.semanticVault = new SemanticVault();
    this.proceduralVault = new ProceduralVault();
    this.router = new MemoryRouter(episodicVault, semanticVault, proceduralVault);
    this.storage = new InMemoryStorageBackend();
    this.summarizer = new DefaultMemorySummarizer();
    this.consolidationExecutor =
        Executors.newSingleThreadExecutor(
            r -> {
              Thread t = new Thread(r, "memory-consolidation");
              t.setDaemon(true);
              return t;
            });
  }

  public VaultController(
      EpisodicVault episodicVault,
      SemanticVault semanticVault,
      ProceduralVault proceduralVault,
      StorageBackend storage,
      MemorySummarizer summarizer) {
    this.episodicVault = Objects.requireNonNull(episodicVault, "episodicVault");
    this.semanticVault = Objects.requireNonNull(semanticVault, "semanticVault");
    this.proceduralVault = Objects.requireNonNull(proceduralVault, "proceduralVault");
    this.router = new MemoryRouter(episodicVault, semanticVault, proceduralVault);
    this.storage = Objects.requireNonNull(storage, "storage");
    this.summarizer = Objects.requireNonNull(summarizer, "summarizer");
    this.consolidationExecutor =
        Executors.newSingleThreadExecutor(
            r -> {
              Thread t = new Thread(r, "memory-consolidation");
              t.setDaemon(true);
              return t;
            });
  }

  // ---------------------------------------------------------------
  // 核心 API
  // ---------------------------------------------------------------

  /**
   * 根据查询上下文从三段式记忆中检索相关信息。
   *
   * <p>对应设计图中 {@code recall(Context) → MemorySet}。
   *
   * @param ctx 查询上下文
   * @return 融合后的记忆集合
   */
  public MemorySet recall(Context ctx) {
    Objects.requireNonNull(ctx, "ctx must not be null");
    return router.route(ctx);
  }

  /**
   * 摄入一次经验/交互到 EpisodicVault（短期记忆）。
   *
   * <p>对应设计图中 {@code memorize(Experience)}。
   *
   * @param exp 本次交互经验
   */
  public void memorize(Experience exp) {
    Objects.requireNonNull(exp, "exp must not be null");

    Step step =
        Step.builder()
            .stepId(exp.sessionId() + "::" + exp.timestamp())
            .action(exp.action())
            .input(exp.observation())
            .output(exp.result())
            .timestamp(exp.timestamp())
            .success(exp.result() != null && !exp.result().startsWith("ERROR"))
            .importance(computeImportance(exp))
            .build();

    episodicVault.appendStep(exp.sessionId(), step);
  }

  /**
   * 结束一个会话，触发异步记忆合并（Consolidation）。
   *
   * <p>对应设计图：
   *
   * <pre>
   *   AgentCore → VaultController.finalizeSession(sessionId)
   *       → EpisodicVault.fetchFullTrace(sessionId)
   *       → MemorySummarizer.summarize(trace)
   *       → SemanticVault.upsertEmbeddings(Facts)
   *       → ProceduralVault.updateSop(Success Patterns)
   * </pre>
   *
   * @param sessionId 会话 ID
   * @return CompletableFuture，完成时包含生成的 {@link Summary}
   */
  public CompletableFuture<Summary> finalizeSession(String sessionId) {
    Objects.requireNonNull(sessionId, "sessionId must not be null");

    return CompletableFuture.supplyAsync(
        () -> {
          // 1. 获取完整 Trace
          List<Step> trace = episodicVault.getTrace(sessionId);

          // 2. 提炼
          Summary summary = summarizer.summarize(trace);

          // 3. 将 Facts 存入 SemanticVault
          for (String fact : summary.facts()) {
            Knowledge knowledge =
                Knowledge.builder()
                    .knowledgeId("fact-" + sessionId + "-" + fact.hashCode())
                    .content(fact)
                    .source("session:" + sessionId)
                    .collection("_consolidated")
                    .build();
            semanticVault.store(knowledge);
          }

          // 4. 将 Success Patterns 存入 ProceduralVault
          for (String pattern : summary.successPatterns()) {
            SOP sop =
                SOP.builder()
                    .sopId("pattern-" + sessionId + "-" + pattern.hashCode())
                    .name("Session Pattern: " + sessionId)
                    .pattern(pattern)
                    .procedure(pattern)
                    .version("0.1.0")
                    .build();
            proceduralVault.register(sop);
          }

          // 5. 持久化摘要
          persistSummary(sessionId, summary);

          return summary;
        },
        consolidationExecutor);
  }

  // ---------------------------------------------------------------
  // 组件访问
  // ---------------------------------------------------------------

  public MemoryRouter router() {
    return router;
  }

  public EpisodicVault episodicVault() {
    return episodicVault;
  }

  public SemanticVault semanticVault() {
    return semanticVault;
  }

  public ProceduralVault proceduralVault() {
    return proceduralVault;
  }

  public StorageBackend storage() {
    return storage;
  }

  public MemorySummarizer summarizer() {
    return summarizer;
  }

  // ---------------------------------------------------------------
  // 内部方法
  // ---------------------------------------------------------------

  /** 根据经验内容计算重要度评分（0.0 ~ 1.0）。 评分标准： - 有错误信息 → 高重要度（供后续学习） - 有结果数据 → 中等重要度 - 简单确认 → 低重要度 */
  private double computeImportance(Experience exp) {
    double score = 0.5;

    // 错误 → 高重要度
    if (exp.result() != null && exp.result().toLowerCase().contains("error")) {
      score += 0.3;
    }
    // 有具体数据 → 中等
    if (exp.observation() != null && exp.observation().length() > 50) {
      score += 0.2;
    }
    // 有结果 → 增加
    if (exp.result() != null && exp.result().length() > 20) {
      score += 0.1;
    }

    return Math.min(1.0, Math.max(0.0, score));
  }

  /** 持久化摘要到 StorageBackend。 */
  private void persistSummary(String sessionId, Summary summary) {
    String key = "summary:" + sessionId;
    byte[] value = summary.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    storage.put(key, value);
  }

  @Override
  public String toString() {
    return "VaultController{" + router + "}";
  }
}
