package org.cland.alice.core.planner.budget;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.cland.alice.core.planner.tree.ThinkingNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Token 预算控制，对应设计文档中的 {@code TokenBudget}。
 *
 * <p>在 Slow Path 的 MCTS 搜索中，设置 TokenBudget 并监控消耗。 当搜索深度或消耗超过阈值时，强制回退到当前最优分支。
 *
 * <p>使用示例：
 *
 * <pre>
 *   TokenBudget budget = TokenBudget.of(1000, 10);
 *   // 或在构建 SlowPathStrategy 时配置
 * </pre>
 */
public final class TokenBudget {

  /** 无限制预算 */
  private static final TokenBudget UNLIMITED =
      new TokenBudget(Integer.MAX_VALUE, Integer.MAX_VALUE);

  /** 最大 Token 消耗 */
  private final int maxTokens;

  /** 最大搜索深度 */
  private final int maxDepth;

  /** 当前已消耗 Token */
  private final AtomicInteger consumedTokens;

  /** 当前深度 */
  private volatile int currentDepth;

  private TokenBudget(int maxTokens, int maxDepth) {
    if (maxTokens <= 0) throw new IllegalArgumentException("maxTokens must be positive");
    if (maxDepth <= 0) throw new IllegalArgumentException("maxDepth must be positive");
    this.maxTokens = maxTokens;
    this.maxDepth = maxDepth;
    this.consumedTokens = new AtomicInteger(0);
    this.currentDepth = 0;
  }

  /**
   * 创建有限预算。
   *
   * @param maxTokens 最大 Token 消耗
   * @param maxDepth 最大搜索深度
   */
  public static TokenBudget of(int maxTokens, int maxDepth) {
    return new TokenBudget(maxTokens, maxDepth);
  }

  /** 获取无限制预算。 */
  public static TokenBudget unlimited() {
    return UNLIMITED;
  }

  // ========== Getters ==========

  public int maxTokens() {
    return maxTokens;
  }

  public int maxDepth() {
    return maxDepth;
  }

  public int consumedTokens() {
    return consumedTokens.get();
  }

  public int currentDepth() {
    return currentDepth;
  }

  public int remainingTokens() {
    return maxTokens - consumedTokens.get();
  }

  /** 判断预算是否已耗尽。 */
  public boolean isExhausted() {
    return consumedTokens.get() >= maxTokens || currentDepth >= maxDepth;
  }

  /**
   * 消耗预算（基于节点访问）。
   *
   * @param node 被访问或生成的节点
   */
  public void consume(ThinkingNode node) {
    Objects.requireNonNull(node, "node must not be null");
    if (isUnlimited()) return; // 无限制预算不计数

    consumedTokens.addAndGet(1); // 每次节点访问计为 1 token

    // 更新深度
    int d = 0;
    ThinkingNode current = node;
    while (current != null) {
      d++;
      current = current.parent();
    }
    this.currentDepth = Math.max(this.currentDepth, d);

    if (isExhausted()) {
      Logger logger = LoggerFactory.getLogger(TokenBudget.class);
      logger.warn(
          "TokenBudget exhausted: tokens={}/{}, depth={}/{}",
          consumedTokens.get(),
          maxTokens,
          currentDepth,
          maxDepth);
    }
  }

  /** 重置预算计数器。 */
  public void reset() {
    consumedTokens.set(0);
    currentDepth = 0;
  }

  /** 判断是否为无限制预算。 */
  public boolean isUnlimited() {
    return this == UNLIMITED;
  }

  @Override
  public String toString() {
    return "TokenBudget{tokens="
        + consumedTokens.get()
        + "/"
        + maxTokens
        + ", depth="
        + currentDepth
        + "/"
        + maxDepth
        + "}";
  }
}
