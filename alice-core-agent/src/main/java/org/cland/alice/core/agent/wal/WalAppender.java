package org.cland.alice.core.agent.wal;

import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WAL Appender — 预写日志写入器。
 *
 * <p>负责 Agent 运行时顺序 Append RawMessage 到 WAL 存储。 提供流式追加、批量刷盘、消息 ID 分配等能力。
 *
 * <p>每个 session 内保证消息 ID 严格单调递增。 写入完成后立即可读（Read-Your-Writes）。
 */
public final class WalAppender {

  private static final Logger log = LoggerFactory.getLogger(WalAppender.class);

  private final WalStore store;

  public WalAppender(WalStore store) {
    this.store = Objects.requireNonNull(store, "store must not be null");
  }

  // ============================================================
  // 单条追加
  // ============================================================

  /**
   * 追加一条 system 消息。
   *
   * @param sessionId 会话 ID
   * @param content 系统消息内容
   * @return 分配的消息 ID
   */
  public long appendSystem(String sessionId, String content) {
    RawMessage msg = RawMessage.system(0, sessionId, content);
    long id = store.appendMessage(msg);
    log.debug("Appended SYSTEM message id={} session={}", id, sessionId);
    return id;
  }

  /**
   * 追加一条 user 消息（纯文本）。
   *
   * @param sessionId 会话 ID
   * @param content 用户输入
   * @return 分配的消息 ID
   */
  public long appendUser(String sessionId, String content) {
    RawMessage msg = RawMessage.user(0, sessionId, content);
    long id = store.appendMessage(msg);
    log.debug("Appended USER message id={} session={}", id, sessionId);
    return id;
  }

  /**
   * 追加一条 user 消息（带名称）。
   *
   * @param sessionId 会话 ID
   * @param content 用户输入
   * @param name 用户标识名
   * @return 分配的消息 ID
   */
  public long appendUser(String sessionId, String content, String name) {
    RawMessage msg = RawMessage.userWithName(0, sessionId, content, name);
    long id = store.appendMessage(msg);
    log.debug("Appended USER message id={} session={} name={}", id, sessionId, name);
    return id;
  }

  /**
   * 追加一条 assistant 消息（纯文本回复）。
   *
   * @param sessionId 会话 ID
   * @param content 助理回复内容
   * @return 分配的消息 ID
   */
  public long appendAssistant(String sessionId, String content) {
    RawMessage msg = RawMessage.assistant(0, sessionId, content);
    long id = store.appendMessage(msg);
    log.debug("Appended ASSISTANT message id={} session={}", id, sessionId);
    return id;
  }

  /**
   * 追加一条 assistant 消息（工具调用指令）。
   *
   * @param sessionId 会话 ID
   * @param toolCalls 工具调用列表
   * @return 分配的消息 ID
   */
  public long appendAssistantToolCalls(String sessionId, List<ToolCall> toolCalls) {
    RawMessage msg = RawMessage.assistantWithToolCalls(0, sessionId, toolCalls);
    long id = store.appendMessage(msg);
    log.debug(
        "Appended ASSISTANT(tool_calls) message id={} session={} calls={}",
        id,
        sessionId,
        toolCalls.size());
    return id;
  }

  /**
   * 追加一条 tool 消息（工具执行结果）。
   *
   * @param sessionId 会话 ID
   * @param toolCallId 配对的工具调用 ID
   * @param content 工具执行结果内容
   * @return 分配的消息 ID
   */
  public long appendToolResult(String sessionId, String toolCallId, String content) {
    RawMessage msg = RawMessage.toolResult(0, sessionId, toolCallId, content);
    long id = store.appendMessage(msg);
    log.debug("Appended TOOL message id={} session={} toolCallId={}", id, sessionId, toolCallId);
    return id;
  }

  // ============================================================
  // 批量追加
  // ============================================================

  /**
   * 批量追加多条消息。
   *
   * @param messages 待追加的消息列表
   * @return 最后一条消息的 ID
   */
  public long appendBatch(List<RawMessage> messages) {
    if (messages == null || messages.isEmpty()) return -1;
    long lastId = store.appendMessages(messages);
    log.debug("Batch appended {} messages, lastId={}", messages.size(), lastId);
    return lastId;
  }

  // ============================================================
  // 读取
  // ============================================================

  /** 获取某个 session 的所有消息。 */
  public List<RawMessage> getAllMessages(String sessionId) {
    return store.getAllMessages(sessionId);
  }

  /** 获取某个 session 中指定 ID 之后的消息（差量读取）。 */
  public List<RawMessage> getMessagesAfter(String sessionId, long afterId) {
    return store.getMessagesAfter(sessionId, afterId, 0);
  }

  /** 获取某个 session 的消息数量。 */
  public int messageCount(String sessionId) {
    return store.messageCount(sessionId);
  }

  // ============================================================
  // 消息链路校验
  // ============================================================

  /**
   * 校验某个 session 的消息链路完整性。 检查每个 assistant.tool_calls 是否有对应的 tool 响应。
   *
   * @param sessionId 会话 ID
   * @return 校验结果，描述缺失配对
   */
  public LinkageValidation validateLinkage(String sessionId) {
    var msgs = store.getAllMessages(sessionId);
    var missing = new java.util.ArrayList<String>();

    for (RawMessage msg : msgs) {
      if ("assistant".equals(msg.role()) && msg.toolCalls() != null) {
        for (ToolCall tc : msg.toolCalls()) {
          boolean found =
              msgs.stream()
                  .anyMatch(m -> "tool".equals(m.role()) && tc.id().equals(m.toolCallId()));
          if (!found) {
            missing.add(tc.id());
          }
        }
      }
    }

    return new LinkageValidation(msgs.size(), missing.size(), missing);
  }

  /** 消息链路校验结果。 */
  public record LinkageValidation(
      int totalMessages, int missingToolCallResponses, List<String> missingToolCallIds) {

    public boolean isComplete() {
      return missingToolCallResponses == 0;
    }
  }
}
