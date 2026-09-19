/*
 * Alice Agent — 子 Agent 契约执行器（B5）
 *
 * 走协议契约：组合根装配 → AgentHost（会话语义）→ InProcessTransport（进程内直调）。
 * 跨进程只需把传输换成 StdioJsonlTransport —— SubAgentManager 不感知差异 ✓。
 */
package org.cland.alice.runtime.subagent;

import java.util.List;
import java.util.function.Function;
import org.cland.alice.agent.proto.ExecutionCmd;
import org.cland.alice.agent.proto.event.StepEvent;
import org.cland.alice.agent.proto.event.StepEventType;
import org.cland.alice.agent.subagent.SubAgentRunner;
import org.cland.alice.core.agent.AgentConfig;
import org.cland.alice.runtime.AgentHost;
import org.cland.alice.runtime.compose.AgentComposer;
import org.cland.alice.runtime.compose.Ids;
import org.cland.alice.runtime.transport.InProcessTransport;

/**
 * 契约子 Agent 执行器 —— 子 Agent 也走 {@code AgentCommand/StepEvent} 契约。
 *
 * <p>与 {@code DefaultSubAgentRunner}（进程内直调 Agent）的差别：这里经过宿主（一轮一锁/事件映射/收口帧） 与传输层，因此"换传输 =
 * 换进程/换协议"对上层透明。
 */
public final class ContractSubAgentRunner implements SubAgentRunner {

  /** 默认单轮预算（子 Agent 通常比交互轮次长）。 */
  public static final long DEFAULT_TIMEOUT_MS = 120_000L;

  private final Function<AgentComposer.Options, AgentComposer.Composed> composer;
  private final long timeoutMs;

  /** 默认：真实组合根 + 120s 预算。 */
  public ContractSubAgentRunner() {
    this(AgentComposer::compose, DEFAULT_TIMEOUT_MS);
  }

  /**
   * @param timeoutMs 单轮预算（毫秒）
   */
  public ContractSubAgentRunner(long timeoutMs) {
    this(AgentComposer::compose, timeoutMs);
  }

  /**
   * 测试/嵌入用：注入组合根函数（返回假引擎）。
   *
   * @param composer 组合根（Options → Composed）
   * @param timeoutMs 单轮预算（毫秒）
   */
  ContractSubAgentRunner(
      Function<AgentComposer.Options, AgentComposer.Composed> composer, long timeoutMs) {
    this.composer = composer;
    this.timeoutMs = timeoutMs;
  }

  @Override
  public String run(String subAgentId, String goal, AgentConfig config) throws Exception {
    AgentComposer.Composed composed =
        composer.apply(
            new AgentComposer.Options(
                subAgentId,
                config.defaultModelId(),
                false, // 子 Agent 临时会话：不落 WAL
                true, // Guardrail 照挂
                List.of("inprocess")));
    AgentHost host = new AgentHost(composed.engine());
    InProcessTransport transport = new InProcessTransport();
    transport.start(host);
    try {
      List<StepEvent> frames =
          transport.await(
              new ExecutionCmd.AcquireGoalCmd(goal, subAgentId, Ids.newTraceId()), timeoutMs);
      String error = firstError(frames);
      if (error != null) {
        throw new IllegalStateException("子 Agent 执行失败：" + error);
      }
      return lastSummary(frames);
    } finally {
      transport.close();
    }
  }

  private static String firstError(List<StepEvent> frames) {
    for (StepEvent f : frames) {
      if (f.type() == StepEventType.ERROR) {
        return String.valueOf(f.payload().getOrDefault("message", "unknown"));
      }
    }
    return null;
  }

  private static String lastSummary(List<StepEvent> frames) {
    String text = "";
    for (StepEvent f : frames) {
      if (f.type() == StepEventType.SUMMARY) {
        Object v = f.payload().get("text");
        if (v != null) {
          text = String.valueOf(v);
        }
      }
    }
    return text;
  }
}
