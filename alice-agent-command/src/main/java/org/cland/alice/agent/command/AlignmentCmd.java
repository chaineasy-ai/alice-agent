/*
 * Alice Agent — Alignment Commands（运行配置）
 *
 * 对应 docs/app/AgentCommand.md 中的 AlignmentCmd 分支：
 *   SwitchModelCmd — /model （切换 LLM 引擎）
 */
package org.cland.alice.agent.command;

import java.time.Instant;
import java.util.Objects;

/**
 * 运行配置指令 — 调整内核参数。
 *
 * <p>继承自 {@link AgentCommand}，密封许可给 {@link SwitchModelCmd}。
 *
 * <p>切换 LLM 后需同步刷新 Verification 模块的审计敏感度。
 */
public sealed interface AlignmentCmd extends AgentCommand {

  /** 配置项的目标值 */
  String value();

  // ──────────────────────────────────────────────────────────────────────────
  // /model — 切换 LLM 引擎
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * 切换模型指令 {@code /model}。
   *
   * <p>动态修改当前使用的 LLM 引擎（如 gpt-4o → claude-3.5-sonnet）， 切换后同步刷新 Verification 模块的审计敏感度。
   *
   * @param modelId 目标模型标识
   */
  record SwitchModelCmd(String modelId, String sessionId, String traceId, Instant timestamp)
      implements AlignmentCmd {

    public SwitchModelCmd {
      Objects.requireNonNull(modelId, "modelId must not be null");
      Objects.requireNonNull(sessionId, "sessionId must not be null");
      Objects.requireNonNull(traceId, "traceId must not be null");
    }

    public SwitchModelCmd(String modelId, String sessionId, String traceId) {
      this(modelId, sessionId, traceId, Instant.now());
    }

    @Override
    public String value() {
      return modelId;
    }
  }
}
