package org.cland.alice.core.agent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.cland.alice.core.agent.wal.SnowflakeIdGenerator;

/**
 * Agent 运行时上下文，贯穿 PPAO 循环的整个生命周期。
 *
 * <p>对应设计文档中 AgentContext，承载：
 *
 * <ul>
 *   <li>用户输入 / 环境信号
 *   <li>会话 ID (sessionId)
 *   <li>循环迭代计数器 (iteration)
 *   <li>最大迭代限制 (maxIterations)
 *   <li>思考链 (thoughtChain)
 *   <li>最终结果
 *   <li>任意扩展属性
 * </ul>
 */
public class AgentContext {

  /** 默认最大迭代次数 */
  public static final int DEFAULT_MAX_ITERATIONS = 10;

  private final String sessionId;
  private final AtomicInteger iteration;
  private final int maxIterations;
  private final Map<String, Object> attributes;
  private final StringBuilder thoughtChain;

  // ========== 状态机字段 ==========

  /** 当前 PPAO 阶段 */
  private volatile Phase currentPhase;

  /** PPAO 阶段枚举 */
  public enum Phase {
    START,
    PERCEIVING,
    PLANNING,
    VERIFYING_PRE,
    ACTING,
    OBSERVING,
    VERIFYING_POST,
    REFLECTING,
    REVISION,
    FINISH
  }

  // ========== 构造 ==========

  public AgentContext() {
    this.sessionId = SnowflakeIdGenerator.generateSessionId();
    this.iteration = new AtomicInteger(0);
    this.maxIterations = DEFAULT_MAX_ITERATIONS;
    this.attributes = new ConcurrentHashMap<>();
    this.thoughtChain = new StringBuilder();
    this.currentPhase = Phase.START;
  }

  public AgentContext(int maxIterations) {
    this.sessionId = SnowflakeIdGenerator.generateSessionId();
    this.iteration = new AtomicInteger(0);
    this.maxIterations = maxIterations;
    this.attributes = new ConcurrentHashMap<>();
    this.thoughtChain = new StringBuilder();
    this.currentPhase = Phase.START;
  }

  public AgentContext(String sessionId) {
    this.sessionId = sessionId;
    this.iteration = new AtomicInteger(0);
    this.maxIterations = DEFAULT_MAX_ITERATIONS;
    this.attributes = new ConcurrentHashMap<>();
    this.thoughtChain = new StringBuilder();
    this.currentPhase = Phase.START;
  }

  public AgentContext(String sessionId, int maxIterations) {
    this.sessionId = sessionId;
    this.iteration = new AtomicInteger(0);
    this.maxIterations = maxIterations;
    this.attributes = new ConcurrentHashMap<>();
    this.thoughtChain = new StringBuilder();
    this.currentPhase = Phase.START;
  }

  // ========== Session ==========

  public String sessionId() {
    return sessionId;
  }

  // ========== 迭代控制 ==========

  /** 获取当前迭代次数 */
  public int iteration() {
    return iteration.get();
  }

  /** 增加迭代计数并返回新值 */
  public int incrementIteration() {
    return iteration.incrementAndGet();
  }

  /** 检查是否达到最大迭代限制 */
  public boolean isMaxIterationsReached() {
    return iteration.get() >= maxIterations;
  }

  /** 获取最大迭代次数 */
  public int maxIterations() {
    return maxIterations;
  }

  // ========== 阶段状态机 ==========

  public Phase currentPhase() {
    return currentPhase;
  }

  /** 安全转换阶段，违反状态机规则时抛出异常。 */
  public synchronized void transitionTo(Phase target) {
    // 允许自转换（idempotent），避免 PPAO 循环中重复设置同一阶段
    if (currentPhase == target) {
      return;
    }
    if (!canTransitionTo(currentPhase, target)) {
      throw new IllegalStateException(
          "Invalid phase transition: " + currentPhase + " -> " + target);
    }
    this.currentPhase = target;
  }

  /** 判断阶段转换是否合法 */
  private static boolean canTransitionTo(Phase from, Phase to) {
    return switch (from) {
      case START -> to == Phase.PERCEIVING;
      case PERCEIVING -> to == Phase.PLANNING;
      case PLANNING -> to == Phase.VERIFYING_PRE || to == Phase.REVISION;
      case VERIFYING_PRE -> to == Phase.ACTING || to == Phase.REVISION;
      case ACTING ->
          to == Phase.ACTING // Micro-ReAct 自循环
              || to == Phase.OBSERVING // Macro: 退出 Micro 进入 Observe
              || to == Phase.REVISION // Micro 内 Revision 跳出
              || to == Phase.FINISH; // 致命错误时直接结束
      case OBSERVING -> to == Phase.VERIFYING_POST || to == Phase.REVISION || to == Phase.FINISH;
      case VERIFYING_POST -> to == Phase.REFLECTING || to == Phase.FINISH || to == Phase.REVISION;
      case REFLECTING -> to == Phase.PLANNING || to == Phase.REVISION || to == Phase.FINISH;
      case REVISION -> to == Phase.PLANNING;
      case FINISH -> false; // 终态
    };
  }

  // ========== 思考链 ==========

  /** 记录思考步骤 */
  public AgentContext appendThought(String thought) {
    if (thoughtChain.length() > 0) {
      thoughtChain.append("\n---\n");
    }
    thoughtChain.append("[").append(iteration.get()).append("] ").append(thought);
    return this;
  }

  /** 获取完整的思考链 */
  public String thoughtChain() {
    return thoughtChain.toString();
  }

  // ========== 属性存取 ==========

  public boolean containsKey(String key) {
    return attributes.containsKey(key);
  }

  public Object get(String key) {
    return attributes.get(key);
  }

  public AgentContext put(String key, Object value) {
    attributes.put(key, value);
    return this;
  }

  public AgentContext putAll(Map<String, Object> map) {
    attributes.putAll(map);
    return this;
  }

  public Object remove(String key) {
    return attributes.remove(key);
  }

  public Map<String, Object> asMap() {
    return Map.copyOf(attributes);
  }

  @Override
  public String toString() {
    return "AgentContext{session='"
        + sessionId
        + "', iteration="
        + iteration.get()
        + ", phase="
        + currentPhase
        + ", attrs="
        + attributes.size()
        + "}";
  }
}
