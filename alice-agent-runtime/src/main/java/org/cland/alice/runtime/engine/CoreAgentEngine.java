/*
 * Alice Agent — core.Agent 的引擎适配器
 */
package org.cland.alice.runtime.engine;

import java.util.Objects;
import org.cland.alice.agent.proto.event.StepEvent;
import org.cland.alice.core.agent.Agent;
import org.cland.alice.core.agent.kernel.EventStream;

/**
 * 把 {@link Agent} 适配成 {@link AgentEngine}。
 *
 * <p>会话标识由组合根显式传入（core 未暴露 sessionId 访问器）；用量暂为 {@link StepEvent.Usage#zero()}（core 尚未上报
 * token；接入后在此补齐）。
 */
public final class CoreAgentEngine implements AgentEngine {

  private final Agent agent;
  private final String sessionId;

  /**
   * @param agent 内核 Agent（不得为 null）
   * @param sessionId 会话标识（与组合根创建 Agent 时一致）
   */
  public CoreAgentEngine(Agent agent, String sessionId) {
    this.agent = Objects.requireNonNull(agent, "agent must not be null");
    this.sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
  }

  @Override
  public String sessionId() {
    return sessionId;
  }

  @Override
  public String ask(String prompt) {
    return agent.ask(prompt);
  }

  @Override
  public void cancel() {
    agent.cancel();
  }

  @Override
  public void injectFeedback(String message) {
    agent.injectFeedback(message);
  }

  @Override
  public void clearMemory() {
    agent.clearMemory();
  }

  @Override
  public String compactContext() {
    return agent.compactContext();
  }

  @Override
  public String currentContext() {
    return agent.getActiveContext();
  }

  @Override
  public EngineEvents events() {
    EventStream core = agent.events();
    return new EngineEvents() {
      @Override
      public void subscribe(Listener listener) {
        core.subscribe(adapt(listener));
      }

      @Override
      public void unsubscribe(Listener listener) {
        core.unsubscribe(adapt(listener));
      }
    };
  }

  /** EngineEvents.Listener → core EventStream.Listener 适配（仅在适配器内出现 core 类型 ✓）。 */
  private static EventStream.Listener adapt(EngineEvents.Listener listener) {
    return new EventStream.Listener() {
      @Override
      public void onThought(String reasoning) {
        listener.onThought(reasoning);
      }

      @Override
      public void onAction(String target, java.util.Map<String, Object> params) {
        listener.onAction(target, params);
      }

      @Override
      public void onObserve(String rawData, String summary, long elapsedMs) {
        listener.onObserve(rawData, summary, elapsedMs);
      }
    };
  }

  @Override
  public StepEvent.Usage lastUsage() {
    // TODO(core): Agent/Executor 上报 token/cost 后在此映射（协议 usage 口径）
    return StepEvent.Usage.zero();
  }
}
