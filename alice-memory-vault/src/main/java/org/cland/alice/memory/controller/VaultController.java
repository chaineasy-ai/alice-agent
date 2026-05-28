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
import org.cland.alice.memory.vault.InMemoryEpisodicVault;
import org.cland.alice.memory.vault.InMemoryProceduralVault;
import org.cland.alice.memory.vault.InMemorySemanticVault;
import org.cland.alice.memory.vault.ProceduralVault;
import org.cland.alice.memory.vault.SemanticVault;

/**
 * 记忆控制器——三段式记忆系统的统一入口。
 *
 * <p>对应设计文档中 VaultController 的角色，以组合模式统一管理 {@link EpisodicVault}、{@link SemanticVault} 和 {@link
 * ProceduralVault}，同时通过 {@link MemoryRouter} 完成检索路由。
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
    this(
        new InMemoryEpisodicVault(),
        new InMemorySemanticVault(),
        new InMemoryProceduralVault(),
        new InMemoryStorageBackend(),
        new DefaultMemorySummarizer());
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
    this.storage = Objects.requireNonNull(storage, "storage");
    this.summarizer = Objects.requireNonNull(summarizer, "summarizer");
    this.router = new MemoryRouter(episodicVault, semanticVault, proceduralVault);
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
   */
  public MemorySet recall(Context ctx) {
    Objects.requireNonNull(ctx, "ctx must not be null");
    return router.route(ctx);
  }

  /**
   * 摄入一次经验/交互到 EpisodicVault（短期记忆）。
   *
   * <p>对应设计图中 {@code memorize(Experience)}。
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
   */
  public CompletableFuture<Summary> finalizeSession(String sessionId) {
    Objects.requireNonNull(sessionId, "sessionId must not be null");

    return CompletableFuture.supplyAsync(
        () -> {
          List<Step> trace = episodicVault.getTrace(sessionId);
          Summary summary = summarizer.summarize(trace);

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

          persistSummary(sessionId, summary);
          return summary;
        },
        consolidationExecutor);
  }

  // ---------------------------------------------------------------
  // 内部方法
  // ---------------------------------------------------------------

  private double computeImportance(Experience exp) {
    double score = 0.5;
    if (exp.result() != null && exp.result().toLowerCase().contains("error")) {
      score += 0.3;
    }
    if (exp.observation() != null && exp.observation().length() > 50) {
      score += 0.2;
    }
    if (exp.result() != null && exp.result().length() > 20) {
      score += 0.1;
    }
    return Math.min(1.0, Math.max(0.0, score));
  }

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
