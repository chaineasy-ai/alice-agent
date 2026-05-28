package org.cland.alice.memory.vault;

import java.util.List;
import org.cland.alice.memory.core.Step;

/**
 * 情景记忆（Episodic Memory）Vault 接口。
 *
 * <p>负责存储 Agent 与环境的交互历史（原始 Traces），支持按 sessionId 检索、遗忘策略等。
 */
public interface EpisodicVault {

  /**
   * 向指定会话追加一条交互步骤。
   *
   * @param sessionId 会话 ID
   * @param step 交互步骤
   */
  void appendStep(String sessionId, Step step);

  /**
   * 获取指定会话的完整 Trace。
   *
   * @param sessionId 会话 ID
   * @return 会话的步骤列表（按添加顺序）
   */
  List<Step> getTrace(String sessionId);

  /**
   * 获取指定会话最近的 N 个步骤。
   *
   * @param sessionId 会话 ID
   * @param n 需要的步骤数
   * @return 最近的 N 个步骤（按时间正序）
   */
  List<Step> getRecentSteps(String sessionId, int n);

  /**
   * 获取指定会话中重要度超过阈值的步骤。
   *
   * @param sessionId 会话 ID
   * @param minImportance 最低重要度阈值
   * @return 重要度 >= minImportance 的步骤列表
   */
  List<Step> getImportantSteps(String sessionId, double minImportance);

  /**
   * 获取指定会话的步骤数量。
   *
   * @param sessionId 会话 ID
   * @return 步骤数量
   */
  int stepCount(String sessionId);

  /**
   * 获取当前活跃的 session 数量。
   *
   * @return session 数量
   */
  int sessionCount();

  /**
   * 获取所有活跃 session ID 的列表。
   *
   * @return session ID 列表
   */
  List<String> getActiveSessionIds();

  /**
   * 清除指定会话的所有步骤。
   *
   * @param sessionId 会话 ID
   */
  void clearSession(String sessionId);

  /** 清除所有 Trace。 */
  void clearAll();

  /**
   * 手动触发遗忘：降低指定 session 中某 Step 的重要度。
   *
   * @param sessionId 会话 ID
   * @param stepId 步骤 ID
   * @param penalty 要降低的重要度值
   */
  void penalizeStep(String sessionId, String stepId, double penalty);
}
