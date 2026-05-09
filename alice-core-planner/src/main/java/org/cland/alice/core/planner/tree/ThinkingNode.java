package org.cland.alice.core.planner.tree;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 思维树中的节点，对应设计文档中的 {@code ThinkingNode}。
 *
 * <p>每个节点包含：
 *
 * <ul>
 *   <li>State (S) — 当前 AgentContext 快照
 *   <li>Action (A) — 规划执行的操作
 *   <li>Value (V) — 模型或验证器给出的该路径得分 (reward)
 *   <li>Thought — LLM 的推理过程文本
 *   <li>访问计数 (visits) — MCTS 回溯用
 * </ul>
 */
public final class ThinkingNode {

  private static final AtomicInteger ID_GEN = new AtomicInteger(0);

  private final int nodeId;
  private final Map<String, Object> state; // AgentContext 快照
  private final String actionType; // 操作类型
  private final String actionTarget; // 操作目标
  private final Map<String, Object> actionParams; // 操作参数
  private final String thought; // LLM 推理过程

  private final AtomicReference<Double> reward; // 奖励值
  private final AtomicInteger visits; // 访问计数
  private final AtomicReference<String> observation; // 执行后的观测结果

  // 父节点引用（用于回溯）
  private volatile ThinkingNode parent;
  private volatile boolean expanded;

  private ThinkingNode(Builder builder) {
    this.nodeId = ID_GEN.incrementAndGet();
    this.state = builder.state != null ? Map.copyOf(builder.state) : Map.of();
    this.actionType = builder.actionType;
    this.actionTarget = builder.actionTarget;
    this.actionParams = builder.actionParams != null ? Map.copyOf(builder.actionParams) : Map.of();
    this.thought = builder.thought;
    this.reward = new AtomicReference<>(builder.reward);
    this.visits = new AtomicInteger(builder.visits);
    this.observation = new AtomicReference<>(builder.observation);
    this.parent = builder.parent;
    this.expanded = false;
  }

  public static Builder builder() {
    return new Builder();
  }

  // ========== Getters ==========

  public int nodeId() {
    return nodeId;
  }

  public Map<String, Object> state() {
    return state;
  }

  public String actionType() {
    return actionType;
  }

  public String actionTarget() {
    return actionTarget;
  }

  public Map<String, Object> actionParams() {
    return actionParams;
  }

  public String thought() {
    return thought;
  }

  public double reward() {
    return reward.get();
  }

  public int visits() {
    return visits.get();
  }

  public String observation() {
    return observation.get();
  }

  public ThinkingNode parent() {
    return parent;
  }

  public boolean expanded() {
    return expanded;
  }

  // ========== MCTS 操作 ==========

  /** 设置父节点 */
  public void setParent(ThinkingNode parent) {
    this.parent = parent;
  }

  /** 标记已展开 */
  public void markExpanded() {
    this.expanded = true;
  }

  /** 更新奖励值（累加，用于 MCTS 回溯） */
  public void addReward(double delta) {
    reward.updateAndGet(r -> r + delta);
  }

  /** 增加访问计数 */
  public void incrementVisits() {
    visits.incrementAndGet();
  }

  /** 设置观测结果 */
  public void setObservation(String observation) {
    this.observation.set(observation);
  }

  /** 设置奖励值（直接覆盖） */
  public void setReward(double reward) {
    this.reward.set(reward);
  }

  // ========== UCT 计算 ==========

  /**
   * 计算 UCT (Upper Confidence Bound Applied to Trees) 值。 用于子节点选择。
   *
   * @param totalVisits 父节点的总访问次数
   * @param explorationConstant 探索常数 C (通常 sqrt(2))
   * @return UCT 值
   */
  public double uct(int totalVisits, double explorationConstant) {
    if (visits.get() == 0) {
      return Double.MAX_VALUE; // 未访问节点优先
    }
    double exploitation = reward.get() / visits.get();
    double exploration = explorationConstant * Math.sqrt(Math.log(totalVisits) / visits.get());
    return exploitation + exploration;
  }

  /** 判断是否为根节点 */
  public boolean isRoot() {
    return parent == null;
  }

  /** 判断是否为叶节点 */
  public boolean isLeaf() {
    return !expanded;
  }

  // ========== 快照 ==========

  /** 创建节点的浅副本（用于序列化 / 持久化）。 */
  public ThinkingNode snapshot() {
    return builder()
        .state(state)
        .actionType(actionType)
        .actionTarget(actionTarget)
        .actionParams(actionParams)
        .thought(thought)
        .reward(reward.get())
        .visits(visits.get())
        .observation(observation.get())
        .parent(parent)
        .build();
  }

  @Override
  public String toString() {
    return "ThinkingNode{id="
        + nodeId
        + ", action="
        + actionType
        + ":"
        + actionTarget
        + ", reward="
        + reward.get()
        + ", visits="
        + visits.get()
        + ", expanded="
        + expanded
        + "}";
  }

  // ========== Builder ==========

  public static final class Builder {
    private Map<String, Object> state;
    private String actionType;
    private String actionTarget;
    private Map<String, Object> actionParams;
    private String thought;
    private double reward;
    private int visits;
    private String observation;
    private ThinkingNode parent;

    private Builder() {}

    public Builder state(Map<String, Object> state) {
      this.state = state;
      return this;
    }

    public Builder actionType(String actionType) {
      this.actionType = actionType;
      return this;
    }

    public Builder actionTarget(String actionTarget) {
      this.actionTarget = actionTarget;
      return this;
    }

    public Builder actionParams(Map<String, Object> p) {
      this.actionParams = p;
      return this;
    }

    public Builder thought(String thought) {
      this.thought = thought;
      return this;
    }

    public Builder reward(double reward) {
      this.reward = reward;
      return this;
    }

    public Builder visits(int visits) {
      this.visits = visits;
      return this;
    }

    public Builder observation(String observation) {
      this.observation = observation;
      return this;
    }

    public Builder parent(ThinkingNode parent) {
      this.parent = parent;
      return this;
    }

    public ThinkingNode build() {
      return new ThinkingNode(this);
    }
  }
}
