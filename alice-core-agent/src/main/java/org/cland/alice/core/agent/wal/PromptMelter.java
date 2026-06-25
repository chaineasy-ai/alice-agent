package org.cland.alice.core.agent.wal;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Prompt Melter — 双轨上下文大熔炉。
 *
 * <p>将 WAL + Checkpoint 双轨数据熔炼为大模型请求的三段式 Prompt：
 *
 * <ol>
 *   <li><b>静态主干区 (Static Trunk)</b> — System Prompt + Static SOP + Tool Schemas（100% 命中 Disk Cache）
 *   <li><b>快照状态区 (Snapshot State)</b> — Checkpoint 还原出的结构化状态变量（轻量，高频变动）
 *   <li><b>极短消息尾部 (Short Tail)</b> — 最近 2 轮的纯文本对话（≤200 Token）
 * </ol>
 */
public final class PromptMelter {

  private static final Logger log = LoggerFactory.getLogger(PromptMelter.class);

  /** 最近消息尾部保留的最大轮数 */
  private static final int DEFAULT_TAIL_ROUNDS = 2;

  /** 尾部最大 Token 估算值（按中英文混合约 2 字符/Token） */
  private static final int MAX_TAIL_CHARS = 400;

  private final WalStore store;

  public PromptMelter(WalStore store) {
    this.store = Objects.requireNonNull(store, "store must not be null");
  }

  /**
   * 熔炼指定 session 的完整上下文。
   *
   * @param sessionId 会话 ID
   * @param staticTrunk 静态主干区内容（System Prompt + SOP + Tool Schemas）
   * @return 三段式 Prompt 结果
   */
  public MeltedPrompt melt(String sessionId, String staticTrunk) {
    Objects.requireNonNull(sessionId, "sessionId must not be null");
    Objects.requireNonNull(staticTrunk, "staticTrunk must not be null");

    // 1. 获取最新 Checkpoint
    var maybeCp = store.getLatestCheckpoint(sessionId);
    var allMessages = store.getAllMessages(sessionId);

    // 2. 构建快照状态区
    String snapshotState = buildSnapshotState(maybeCp.orElse(null));

    // 3. 构建极短消息尾部
    String shortTail = buildShortTail(allMessages, DEFAULT_TAIL_ROUNDS);

    // 4. 估算 Token
    int staticTokens = estimateTokens(staticTrunk);
    int snapshotTokens = estimateTokens(snapshotState);
    int tailTokens = estimateTokens(shortTail);

    return new MeltedPrompt(
        staticTrunk,
        snapshotState,
        shortTail,
        staticTokens,
        snapshotTokens,
        tailTokens,
        maybeCp.map(Checkpoint::lastAppliedMessageId).orElse(0L),
        allMessages.size());
  }

  /**
   * 构建快照状态区。
   *
   * <p>将 Checkpoint 中的结构化状态变量格式化为纯文本摘要， 替代原始消息中的大量垃圾日志。
   */
  private String buildSnapshotState(Checkpoint cp) {
    if (cp == null) {
      return "[State] Fresh session, no checkpoint\n";
    }

    var sb = new StringBuilder();
    sb.append("[State] ").append(cp.stateNode()).append("\n");

    if (cp.variableSnapshot() != null && !cp.variableSnapshot().isEmpty()) {
      for (var entry : cp.variableSnapshot().entrySet()) {
        String key = entry.getKey();
        Object value = entry.getValue();

        // 跳过内部追踪字段和过长值
        if (key.startsWith("lastReplayed") || key.startsWith("checkpointTime")) continue;

        String strVal = value != null ? value.toString() : "null";
        if (strVal.length() > 200) {
          strVal = strVal.substring(0, 200) + "...";
        }
        sb.append("  ").append(key).append(" = ").append(strVal).append("\n");
      }
    }

    if (cp.planSnapshot() != null && !cp.planSnapshot().isEmpty()) {
      String plan = cp.planSnapshot();
      if (plan.length() > 500) {
        plan = plan.substring(0, 500) + "...";
      }
      sb.append("[Plan] ").append(plan).append("\n");
    }

    return sb.toString();
  }

  /**
   * 构建极短消息尾部。
   *
   * <p>只保留最近 N 轮纯文本对话（完全剥离 tool_calls 和原始 tool 返回）。 超过 MAX_TAIL_CHARS 时从头部截断。
   */
  private String buildShortTail(List<RawMessage> allMessages, int rounds) {
    if (allMessages.isEmpty()) return "[Recent Messages]\n(empty)\n";

    // 从后往前收集最近的纯文本消息对
    var tailMessages = new ArrayList<RawMessage>();
    int roundCount = 0;

    for (int i = allMessages.size() - 1; i >= 0 && roundCount < rounds; i--) {
      RawMessage msg = allMessages.get(i);
      // 只保留 user 和 assistant（纯文本）消息
      if (("user".equals(msg.role()) || "assistant".equals(msg.role()))
          && msg.content() != null
          && !msg.content().isEmpty()) {
        tailMessages.add(0, msg); // 头部插入，保持顺序
      }
      if ("user".equals(msg.role())) {
        roundCount++;
      }
    }

    // 组装为文本
    var sb = new StringBuilder();
    sb.append("[Recent Messages]\n");
    for (RawMessage msg : tailMessages) {
      sb.append(msg.role()).append(": ");
      String content = msg.content();
      if (content.length() > 300) {
        content = content.substring(0, 300) + "...";
      }
      sb.append(content).append("\n");
    }

    // 截断尾部
    String result = sb.toString();
    if (result.length() > MAX_TAIL_CHARS) {
      result = result.substring(result.length() - MAX_TAIL_CHARS);
      result = "...(truncated)\n" + result;
    }

    return result;
  }

  // ============================================================
  // 工具方法
  // ============================================================

  /** 粗略估算字符数对应的 Token 数（按 2 字符/Token）。 */
  private int estimateTokens(String text) {
    if (text == null || text.isEmpty()) return 0;
    // 粗略估算：英文 1 字符 ≈ 0.25 token，中文 1 字 ≈ 1.5 token
    int chineseCount = 0;
    int asciiCount = 0;
    for (char c : text.toCharArray()) {
      if (c > 0x7F) {
        chineseCount++;
      } else if (c != '\n' && c != '\r') {
        asciiCount++;
      }
    }
    return (int) (asciiCount * 0.25 + chineseCount * 1.5);
  }

  // ============================================================
  // 结果类型
  // ============================================================

  /**
   * 熔炼后的三段式 Prompt。
   *
   * @param staticTrunk 静态主干区（命中 Disk Cache）
   * @param snapshotState 快照状态区（结构化变量）
   * @param shortTail 极短消息尾部
   * @param staticTokens 静态主干 Token 估算
   * @param snapshotTokens 快照状态 Token 估算
   * @param tailTokens 尾部 Token 估算
   * @param lastAppliedId 最后应用的消息 ID
   * @param totalMessages 总消息数
   */
  public record MeltedPrompt(
      String staticTrunk,
      String snapshotState,
      String shortTail,
      int staticTokens,
      int snapshotTokens,
      int tailTokens,
      long lastAppliedId,
      int totalMessages) {

    /** 获取完整 Prompt（三段拼接）。 */
    public String fullPrompt() {
      return staticTrunk + "\n\n" + snapshotState + "\n" + shortTail;
    }

    /** 获取总 Token 估算。 */
    public int totalTokens() {
      return staticTokens + snapshotTokens + tailTokens;
    }

    /** 获取缓存 Key（用于 Disk Prompt Cache）。 */
    public String cacheKey() {
      return "prompt:" + lastAppliedId;
    }
  }
}
