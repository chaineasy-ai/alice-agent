package org.cland.alice.core.agent.lifecycle;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ReAct 循环上下文 — 跟踪 ReAct 循环的状态。
 *
 * <p>记录迭代次数、token 消耗、行动历史等运行时元数据。 由 {@link ReAct#loop(Map, ReActContext)} 使用和维护。
 *
 * <p>使用示例：
 *
 * <pre>{@code
 * ReActContext rctx = ReActContext.builder()
 *     .maxIterations(20)
 *     .maxTokens(1000)
 *     .build();
 * Map<String, Object> result = react.loop(ctx, rctx);
 * }</pre>
 */
public final class ReActContext {

  /** 默认最大迭代次数 */
  private static final int DEFAULT_MAX_ITERATIONS = 10;

  /** 默认最大 Token 消耗（-1 表示无限制） */
  private static final int DEFAULT_MAX_TOKENS = -1;

  private final int maxIterations;
  private final int maxTokens;
  private final AtomicInteger iteration;
  private final AtomicInteger tokensConsumed;
  private volatile boolean finished;

  /** 行动历史记录 */
  private final List<ActionRecord> history;

  private ReActContext(Builder builder) {
    this.maxIterations = builder.maxIterations > 0 ? builder.maxIterations : DEFAULT_MAX_ITERATIONS;
    this.maxTokens = builder.maxTokens;
    this.iteration = new AtomicInteger(0);
    this.tokensConsumed = new AtomicInteger(0);
    this.finished = false;
    this.history = new ArrayList<>();
  }

  /**
   * 使用默认配置创建 ReActContext。
   *
   * @return 默认的 ReActContext（10 次迭代，无 Token 限制）
   */
  public static ReActContext create() {
    return builder().build();
  }

  public static Builder builder() {
    return new Builder();
  }

  // ========== 循环控制 ==========

  /** 增加迭代计数并返回新值。 */
  public int incrementIteration() {
    return iteration.incrementAndGet();
  }

  /** 获取当前迭代次数。 */
  public int iteration() {
    return iteration.get();
  }

  /** 标记循环结束。 */
  public void markFinished() {
    this.finished = true;
  }

  /** 判断循环是否已结束。 */
  public boolean isFinished() {
    return finished || iteration.get() >= maxIterations;
  }

  /** 检查预算是否耗尽（Token 或迭代次数）。 */
  public boolean isBudgetExhausted() {
    if (iteration.get() >= maxIterations) return true;
    return maxTokens > 0 && tokensConsumed.get() >= maxTokens;
  }

  // ========== 预算追踪 ==========

  /** 记录一次行动并消耗 token 预算。 */
  public void recordAction(String type, Object observation) {
    history.add(new ActionRecord(type, observation, System.currentTimeMillis()));
    // 每次行动估算消耗 1 token（实际应由子类重写精确计算）
    tokensConsumed.incrementAndGet();
  }

  /** 获取已消耗的 Token 数。 */
  public int tokensConsumed() {
    return tokensConsumed.get();
  }

  /** 获取最大 Token 限额（-1 表示无限制）。 */
  public int maxTokens() {
    return maxTokens;
  }

  /** 获取最大迭代次数。 */
  public int maxIterations() {
    return maxIterations;
  }

  // ========== 历史记录 ==========

  /** 获取行动历史的不可变快照。 */
  public List<ActionRecord> history() {
    return List.copyOf(history);
  }

  /** 最近一次行动记录。 */
  public ActionRecord lastAction() {
    return history.isEmpty() ? null : history.get(history.size() - 1);
  }

  // ========== 记录类型 ==========

  /** ReAct 循环中的一次行动记录。 */
  public record ActionRecord(String type, Object observation, long timestampMs) {

    public ActionRecord {
      Objects.requireNonNull(type, "type must not be null");
    }

    @Override
    public String toString() {
      return "ActionRecord{type='" + type + "', obs=" + observation + "}";
    }
  }

  // ========== Builder ==========

  public static final class Builder {
    private int maxIterations;
    private int maxTokens = DEFAULT_MAX_TOKENS;

    private Builder() {}

    /** 设置最大迭代次数。 */
    public Builder maxIterations(int maxIterations) {
      this.maxIterations = maxIterations;
      return this;
    }

    /** 设置最大 Token 消耗限额。 */
    public Builder maxTokens(int maxTokens) {
      this.maxTokens = maxTokens;
      return this;
    }

    public ReActContext build() {
      return new ReActContext(this);
    }
  }
}
