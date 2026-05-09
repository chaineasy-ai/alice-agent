package org.cland.alice.memory;

/**
 * 记忆提炼器——将 EpisodicVault 的原始 Trace 转化为 可供 SemanticVault 和 ProceduralVault 消费的结构化知识。
 *
 * <p>对应设计文档中 "The Consolidation Process" 的 Summarizer 角色。
 */
@FunctionalInterface
public interface MemorySummarizer {

  /**
   * 从一段原始会话 Trace 中提炼出结构化摘要。
   *
   * @param trace 原始交互步骤列表（按时间正序）
   * @return 包含事实与成功模式的摘要
   */
  Summary summarize(java.util.List<Step> trace);
}
