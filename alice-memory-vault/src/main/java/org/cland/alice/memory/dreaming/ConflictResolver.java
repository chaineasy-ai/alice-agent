package org.cland.alice.memory.dreaming;

import java.util.List;
import java.util.Objects;
import org.cland.alice.memory.vault.SemanticVault;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 知识冲突解析器 — 比较新的事实与 SemanticVault 中已有知识， 标记矛盾旧条目为 DEPRECATED，将较新事实提升为 ACTIVE。
 *
 * <p>v1 实现使用基于时间戳的启发式规则： - 较新事实获胜 - 相等时间戳 → MANUAL_REVIEW - 无冲突 → 直接存储
 *
 * <p>v2 将增加语义相似度比较。
 */
public final class ConflictResolver {

  private static final Logger log = LoggerFactory.getLogger(ConflictResolver.class);

  private static final String DREAMING_FACTS_COLLECTION = "_dreaming_facts";

  /** 相等时间戳容差（毫秒） */
  private static final long TIMESTAMP_TOLERANCE_MS = 1000;

  private final SemanticVault semanticVault;

  public ConflictResolver(SemanticVault semanticVault) {
    this.semanticVault = Objects.requireNonNull(semanticVault, "semanticVault must not be null");
  }

  /**
   * 解析一批事实与 SemanticVault 已有知识的冲突。
   *
   * @param facts PromptMelter 提取的事实
   * @param sessionId 源会话（用于溯源）
   * @return 解析结果统计
   */
  public ResolveResult resolve(List<DreamingFact> facts, String sessionId) {
    Objects.requireNonNull(facts, "facts must not be null");
    Objects.requireNonNull(sessionId, "sessionId must not be null");

    int factsProcessed = 0;
    int newFacts = 0;
    int deprecatedFacts = 0;
    int manualReviewFacts = 0;

    for (DreamingFact fact : facts) {
      // 跳过低置信度事实
      if (fact.confidence() < 0.5) {
        log.trace("[ConflictResolver] Skipping low-confidence fact: {}", fact.factId());
        continue;
      }

      factsProcessed++;

      // 在 "_dreaming_facts" collection 中搜索精确内容匹配
      // 使用 getAll() 获取全部知识，然后按空白符归一化后的内容做精确匹配
      List<org.cland.alice.memory.core.Knowledge> allInCollection =
          semanticVault.getAll(DREAMING_FACTS_COLLECTION);
      String normalizedFactContent = normalizeWhitespace(fact.content());

      boolean resolved = false;
      for (var existing : allInCollection) {
        // 精确内容匹配（经过空白符归一化后）
        if (!normalizeWhitespace(existing.content()).equals(normalizedFactContent)) {
          continue;
        }

        // 时间戳比较
        if (fact.timestamp() > existing.createdAt() + TIMESTAMP_TOLERANCE_MS) {
          // 新事实较新 → 标记旧知识为 DEPRECATED
          String deprecatedContent = "(DEPRECATED) " + existing.content();
          org.cland.alice.memory.core.Knowledge deprecated =
              org.cland.alice.memory.core.Knowledge.builder()
                  .knowledgeId("deprecated-" + existing.knowledgeId())
                  .content(deprecatedContent)
                  .source(existing.source())
                  .collection(DREAMING_FACTS_COLLECTION)
                  .createdAt(existing.createdAt())
                  .build();
          semanticVault.remove(DREAMING_FACTS_COLLECTION, existing.knowledgeId());
          semanticVault.store(DREAMING_FACTS_COLLECTION, deprecated);

          // 存储新事实
          semanticVault.store(
              DREAMING_FACTS_COLLECTION,
              org.cland.alice.memory.core.Knowledge.builder()
                  .knowledgeId(fact.factId())
                  .content(fact.content())
                  .source("dreaming:" + sessionId)
                  .collection(DREAMING_FACTS_COLLECTION)
                  .createdAt(fact.timestamp())
                  .build());

          deprecatedFacts++;
          resolved = true;
          log.debug(
              "[ConflictResolver] Deprecated old fact '{}' with new fact '{}'",
              existing.knowledgeId(),
              fact.factId());

        } else if (Math.abs(fact.timestamp() - existing.createdAt()) <= TIMESTAMP_TOLERANCE_MS) {
          // 时间戳相等 → 标记为 MANUAL_REVIEW
          String reviewContent = "(MANUAL_REVIEW) " + existing.content();
          org.cland.alice.memory.core.Knowledge review =
              org.cland.alice.memory.core.Knowledge.builder()
                  .knowledgeId("review-" + existing.knowledgeId())
                  .content(reviewContent)
                  .source(existing.source())
                  .collection(DREAMING_FACTS_COLLECTION)
                  .createdAt(existing.createdAt())
                  .build();
          semanticVault.remove(DREAMING_FACTS_COLLECTION, existing.knowledgeId());
          semanticVault.store(DREAMING_FACTS_COLLECTION, review);

          // 仍存储新事实
          semanticVault.store(
              DREAMING_FACTS_COLLECTION,
              org.cland.alice.memory.core.Knowledge.builder()
                  .knowledgeId(fact.factId())
                  .content(fact.content())
                  .source("dreaming:" + sessionId)
                  .collection(DREAMING_FACTS_COLLECTION)
                  .createdAt(fact.timestamp())
                  .build());

          manualReviewFacts++;
          resolved = true;
          log.debug(
              "[ConflictResolver] Marked '{}' for MANUAL_REVIEW due to equal timestamps",
              existing.knowledgeId());
        }
        // 如果现有事实较新，则不操作
      }

      if (!resolved) {
        // 无冲突：直接存储为新的 ACTIVE 知识
        semanticVault.store(
            DREAMING_FACTS_COLLECTION,
            org.cland.alice.memory.core.Knowledge.builder()
                .knowledgeId(fact.factId())
                .content(fact.content())
                .source("dreaming:" + sessionId)
                .collection(DREAMING_FACTS_COLLECTION)
                .createdAt(fact.timestamp())
                .build());
        newFacts++;
        log.trace("[ConflictResolver] New fact stored: {}", fact.factId());
      }
    }

    ResolveResult result =
        new ResolveResult(factsProcessed, newFacts, deprecatedFacts, manualReviewFacts);
    log.info(
        "[ConflictResolver] Session={}: {} processed, {} new, {} deprecated, {} manual-review",
        sessionId,
        factsProcessed,
        newFacts,
        deprecatedFacts,
        manualReviewFacts);
    return result;
  }

  /** 空白符归一化（用于内容比较）。 */
  static String normalizeWhitespace(String s) {
    if (s == null) return "";
    return s.trim().replaceAll("\\s+", " ");
  }

  /**
   * 冲突解析结果统计。
   *
   * @param factsProcessed 处理的事实数（跳过低置信度后）
   * @param newFacts 新增事实数（无冲突）
   * @param deprecatedFacts 被标记为 DEPRECATED 的旧事实数
   * @param manualReviewFacts 需人工审查的事实数
   */
  public record ResolveResult(
      int factsProcessed, int newFacts, int deprecatedFacts, int manualReviewFacts) {

    public ResolveResult {
      if (factsProcessed < 0) throw new IllegalArgumentException("factsProcessed must be >= 0");
      if (newFacts < 0) throw new IllegalArgumentException("newFacts must be >= 0");
      if (deprecatedFacts < 0) throw new IllegalArgumentException("deprecatedFacts must be >= 0");
      if (manualReviewFacts < 0)
        throw new IllegalArgumentException("manualReviewFacts must be >= 0");
    }
  }
}
