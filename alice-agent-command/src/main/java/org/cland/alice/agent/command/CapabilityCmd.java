/*
 * Alice Agent — Capability Commands（能力装载）
 *
 * 对应 docs/app/AgentCommand.md 中的 CapabilityCmd 分支：
 *   RegisterSkillCmd  — /skill （加载 MCP / 工具集）
 *   UpdateRulesCmd    — /rules （加载预设 Prompt / 规则）
 *   ReloadKernelCmd   — /reload（强制刷新所有 Resource）
 */
package org.cland.alice.agent.command;

import java.time.Instant;
import java.util.Objects;

/**
 * 能力装载指令 — 需要 Reload 的静态/动态资源。
 *
 * <p>继承自 {@link AgentCommand}，密封许可给 {@link RegisterSkillCmd}、{@link UpdateRulesCmd}、 {@link
 * ReloadKernelCmd}。
 *
 * <p>时序（对应 AgentCommand.md §3）：
 *
 * <ol>
 *   <li>Facade 分发 CapabilityCmd → AliceAgent
 *   <li>AliceAgent → ResourceLoader 查找并加载资源
 *   <li>AliceAgent → Agent Core: attach(Capability)
 *   <li>Core → Planner: refreshSystemKnowledge()
 *   <li>Core → Facade: AckCommand
 * </ol>
 */
public sealed interface CapabilityCmd extends AgentCommand {

  /** 能力资源的标识（文件路径、MCP 端点、规则名等） */
  String resource();

  // ──────────────────────────────────────────────────────────────────────────
  // /skill — 注册工具
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * 注册工具指令 {@code /skill}。
   *
   * <p>触发 ToolGateway 扫描新工具定义，并通知 Planner 更新 API Schema 认知。
   *
   * @param skillRef 工具引用（MCP 配置文件路径、工具名称等）
   */
  record RegisterSkillCmd(String skillRef, String sessionId, String traceId, Instant timestamp)
      implements CapabilityCmd {

    public RegisterSkillCmd {
      Objects.requireNonNull(skillRef, "skillRef must not be null");
      Objects.requireNonNull(sessionId, "sessionId must not be null");
      Objects.requireNonNull(traceId, "traceId must not be null");
    }

    public RegisterSkillCmd(String skillRef, String sessionId, String traceId) {
      this(skillRef, sessionId, traceId, Instant.now());
    }

    @Override
    public String resource() {
      return skillRef;
    }
  }

  // ──────────────────────────────────────────────────────────────────────────
  // /rules — 注册提示词
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * 注册规则/提示词指令 {@code /rules}。
   *
   * <p>触发 Memory 加载 .prompt 文件，并通知 Planner 重新 Rebase 整个 System Prompt。
   *
   * @param rulesRef 规则文件路径或规则标识
   */
  record UpdateRulesCmd(String rulesRef, String sessionId, String traceId, Instant timestamp)
      implements CapabilityCmd {

    public UpdateRulesCmd {
      Objects.requireNonNull(rulesRef, "rulesRef must not be null");
      Objects.requireNonNull(sessionId, "sessionId must not be null");
      Objects.requireNonNull(traceId, "traceId must not be null");
    }

    public UpdateRulesCmd(String rulesRef, String sessionId, String traceId) {
      this(rulesRef, sessionId, traceId, Instant.now());
    }

    @Override
    public String resource() {
      return rulesRef;
    }
  }

  // ──────────────────────────────────────────────────────────────────────────
  // /reload — 热重载
  // ──────────────────────────────────────────────────────────────────────────

  /**
   * 热重载指令 {@code /reload}。
   *
   * <p>强制重新扫描所有外部能力源，确保本地文件变更立即生效。 无需额外资源引用，扫描全部能力源。
   */
  record ReloadKernelCmd(String sessionId, String traceId, Instant timestamp)
      implements CapabilityCmd {

    public ReloadKernelCmd {
      Objects.requireNonNull(sessionId, "sessionId must not be null");
      Objects.requireNonNull(traceId, "traceId must not be null");
    }

    public ReloadKernelCmd(String sessionId, String traceId) {
      this(sessionId, traceId, Instant.now());
    }

    @Override
    public String resource() {
      return "*";
    }
  }
}
