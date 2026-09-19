/*
 * Alice Agent — 组合根（唯一装配点）
 *
 * 为什么需要：门面曾各自 new Agent + 拼装 WAL/Guardrail/工具（默认值已漂移、口径不一 ✗）。
 * 现在统一为 AgentComposer；门面只拿 AgentEngine（**不暴露 core 类型**，保持门面零 core 依赖 ✓）。
 */
package org.cland.alice.runtime.compose;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.cland.alice.core.agent.Agent;
import org.cland.alice.core.agent.AgentConfig;
import org.cland.alice.core.agent.guardrail.GuardrailVerificatorAdapter;
import org.cland.alice.core.agent.wal.FileWalStore;
import org.cland.alice.core.agent.wal.WalSession;
import org.cland.alice.runtime.engine.AgentEngine;
import org.cland.alice.runtime.engine.CoreAgentEngine;

/**
 * 组合根 —— 装配 Agent 并交给宿主驱动（默认：WAL + Guardrail + 内置工具）。
 *
 * <p>用法：
 *
 * <pre>{@code
 * var composed = AgentComposer.compose(AgentComposer.Options.defaults());
 * var host = new AgentHost(composed.engine());
 * }</pre>
 */
public final class AgentComposer {

  private AgentComposer() {}

  /**
   * 装配选项。
   *
   * @param sessionId 会话 ID（空 = 自动生成）
   * @param model 模型 ID（空 = {@code Agent.initModelProvider()} 探测默认）
   * @param wal 是否挂 WAL（{@code ~/.alice/wal/<sessionId>}）
   * @param guardrail 是否挂 Guardrail 校验桥
   * @param transports 已启用的传输名（仅用于启动横幅展示）
   */
  public record Options(
      String sessionId, String model, boolean wal, boolean guardrail, List<String> transports) {

    /** 便捷构造：传输默认 inprocess。 */
    public Options(String sessionId, String model, boolean wal, boolean guardrail) {
      this(sessionId, model, wal, guardrail, List.of("inprocess"));
    }

    public Options {
      transports =
          transports == null || transports.isEmpty()
              ? List.of("inprocess")
              : List.copyOf(transports);
    }

    /** 默认：自动会话 + 默认模型 + WAL + Guardrail。 */
    public static Options defaults() {
      return new Options(null, null, true, true);
    }
  }

  /**
   * 装配结果。
   *
   * @param engine 引擎端口（宿主直接可用；**不暴露 core 类型** ✓）
   * @param sessionId 实际会话 ID
   * @param banner 启动横幅数据（各门面统一开场输出）
   */
  public record Composed(AgentEngine engine, String sessionId, StartupBanner.Data banner) {

    public Composed {
      Objects.requireNonNull(engine, "engine must not be null");
      Objects.requireNonNull(sessionId, "sessionId must not be null");
      Objects.requireNonNull(banner, "banner must not be null");
    }
  }

  /**
   * 按选项装配。
   *
   * @param options 装配选项（null ⇒ {@link Options#defaults()}）
   * @return 装配结果
   */
  public static Composed compose(Options options) {
    Options opt = options == null ? Options.defaults() : options;
    String sessionId =
        opt.sessionId() == null || opt.sessionId().isBlank() ? Ids.newSessionId() : opt.sessionId();
    String model =
        opt.model() == null || opt.model().isBlank() ? Agent.initModelProvider() : opt.model();
    AgentConfig config =
        AgentConfig.builder()
            .defaultModelId(model)
            .maxIterations(AgentConfig.DEFAULT_MAX_ITERATIONS)
            .build();
    Agent agent = Agent.createDefault(config); // 工具注册 + 默认装配
    if (opt.wal()) {
      agent =
          agent.withWal(
              new WalSession(
                  new FileWalStore(
                      Path.of(System.getProperty("user.home"), ".alice", "wal", sessionId))));
    }
    if (opt.guardrail()) {
      agent = agent.withGuardrail(new GuardrailVerificatorAdapter());
    }
    StartupBanner.Data banner = StartupBanner.collect(agent, sessionId, opt.transports());
    return new Composed(new CoreAgentEngine(agent, sessionId), sessionId, banner);
  }
}
