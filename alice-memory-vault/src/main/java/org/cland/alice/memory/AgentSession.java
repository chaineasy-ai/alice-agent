package org.cland.alice.memory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 会话内存，负责管理短期和长期记忆。
 *
 * <p>对应设计文档中 MemoryVault (M) 的角色，支持：
 *
 * <ul>
 *   <li>按 sessionId 存取上下文记忆
 *   <li>持久化观测结果
 *   <li>长期 / 短期记忆的分层管理
 * </ul>
 */
public class AgentSession {

  /** 短期记忆（当前会话） */
  private final Map<String, StringBuilder> shortTermMemory = new ConcurrentHashMap<>();

  /** 长期记忆（跨会话） */
  private final Map<String, Map<String, Object>> longTermMemory = new ConcurrentHashMap<>();

  /**
   * 持久化一条观测记录到指定会话的记忆中。
   *
   * @param sessionId 会话 ID
   * @param data 观测数据
   */
  public void persist(String sessionId, String data) {
    shortTermMemory.computeIfAbsent(sessionId, k -> new StringBuilder()).append(data).append("\n");
  }

  /**
   * 获取指定会话的短期记忆。
   *
   * @param sessionId 会话 ID
   * @return 会话的短期记忆内容
   */
  public String getShortTerm(String sessionId) {
    StringBuilder sb = shortTermMemory.get(sessionId);
    return sb != null ? sb.toString() : "";
  }

  /**
   * 存储一条长期记忆。
   *
   * @param key 记忆键
   * @param value 记忆值
   */
  public void putLongTerm(String key, Object value) {
    longTermMemory.computeIfAbsent("_global", k -> new ConcurrentHashMap<>()).put(key, value);
  }

  /**
   * 获取长期记忆。
   *
   * @param key 记忆键
   * @return 记忆值
   */
  public Object getLongTerm(String key) {
    Map<String, Object> global = longTermMemory.get("_global");
    return global != null ? global.get(key) : null;
  }

  /** 清除指定会话的短期记忆。 */
  public void clearSession(String sessionId) {
    shortTermMemory.remove(sessionId);
  }
}
