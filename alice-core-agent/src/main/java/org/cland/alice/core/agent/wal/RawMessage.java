package org.cland.alice.core.agent.wal;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * RAW 消息实体 — 充当 WAL (Write-Ahead Log) 中的一条记录。
 *
 * <p>遵循 OpenAI Chat Completions 消息对象规范，兼容纯文本、多模态、工具调用三种场景。
 *
 * <p>每个 RawMessage 是 Agent 运行时交互的最小原子单元，以 Append-Only 方式写入 WAL。
 *
 * <p>角色对应关系：
 *
 * <ul>
 *   <li>{@code system} — 系统/开发者消息
 *   <li>{@code user} — 用户输入（纯文本或多模态）
 *   <li>{@code assistant} — 助理回复或工具调用指令
 *   <li>{@code tool} — 工具执行结果回传
 *   <li>{@code compact} — 压缩摘要：由 {@code /compact} 命令触发，将历史对话提炼为一段摘要
 * </ul>
 *
 * @param messageId 单调递增消息 ID（全局或会话级）
 * @param sessionId 所属会话 ID
 * @param role 消息角色: system | user | assistant | tool | compact
 * @param content 消息内容（纯文本），当 tool_calls 存在时为 null
 * @param toolCalls 工具调用指令列表（仅 assistant 角色）
 * @param toolCallId 工具调用回传配对 ID（仅 tool 角色）
 * @param name 可选角色标识名
 * @param timestamp 记录时间戳（毫秒）
 * @param metadata 扩展元数据（token 消耗、延迟等）
 */
public record RawMessage(
    long messageId,
    String sessionId,
    String role,
    String content,
    List<ToolCall> toolCalls,
    String toolCallId,
    String name,
    long timestamp,
    Map<String, Object> metadata) {

  /** 有效角色枚举 */
  public static final List<String> VALID_ROLES =
      List.of("system", "user", "assistant", "tool", "compact");

  public RawMessage {
    Objects.requireNonNull(sessionId, "sessionId must not be null");
    Objects.requireNonNull(role, "role must not be null");
    if (!VALID_ROLES.contains(role)) {
      throw new IllegalArgumentException(
          "Invalid role: " + role + ". Must be one of " + VALID_ROLES);
    }
    if (content == null && (toolCalls == null || toolCalls.isEmpty()) && !"tool".equals(role)) {
      throw new IllegalArgumentException(
          "content must not be null when toolCalls is empty for role: " + role);
    }
    if ("tool".equals(role) && toolCallId == null) {
      throw new IllegalArgumentException("toolCallId must not be null for role: tool");
    }
    if (timestamp <= 0) {
      timestamp = System.currentTimeMillis();
    }
    if (metadata == null) {
      metadata = Map.of();
    }
  }

  // ========== 工厂方法 ==========

  /** 创建 system 消息 */
  public static RawMessage system(long messageId, String sessionId, String content) {
    return new RawMessage(messageId, sessionId, "system", content, null, null, null, 0, Map.of());
  }

  /** 创建 user 消息（纯文本） */
  public static RawMessage user(long messageId, String sessionId, String content) {
    return new RawMessage(messageId, sessionId, "user", content, null, null, null, 0, Map.of());
  }

  /** 创建 assistant 消息（纯文本回复） */
  public static RawMessage assistant(long messageId, String sessionId, String content) {
    return new RawMessage(
        messageId, sessionId, "assistant", content, null, null, null, 0, Map.of());
  }

  /** 创建 assistant 消息（工具调用） */
  public static RawMessage assistantWithToolCalls(
      long messageId, String sessionId, List<ToolCall> toolCalls) {
    return new RawMessage(
        messageId, sessionId, "assistant", null, toolCalls, null, null, 0, Map.of());
  }

  /** 创建 tool 消息（工具执行结果） */
  public static RawMessage toolResult(
      long messageId, String sessionId, String toolCallId, String content) {
    return new RawMessage(
        messageId, sessionId, "tool", content, null, toolCallId, null, 0, Map.of());
  }

  /** 创建带名称的 user 消息 */
  public static RawMessage userWithName(
      long messageId, String sessionId, String content, String name) {
    return new RawMessage(messageId, sessionId, "user", content, null, null, name, 0, Map.of());
  }

  /** 创建 compact 消息（压缩摘要） */
  public static RawMessage compact(long messageId, String sessionId, String content) {
    return new RawMessage(messageId, sessionId, "compact", content, null, null, null, 0, Map.of());
  }

  @Override
  public String toString() {
    return "RawMessage{id=%d, session='%s', role=%s, hasContent=%s, hasToolCalls=%s}"
        .formatted(
            messageId, sessionId, role, content != null, toolCalls != null && !toolCalls.isEmpty());
  }
}
