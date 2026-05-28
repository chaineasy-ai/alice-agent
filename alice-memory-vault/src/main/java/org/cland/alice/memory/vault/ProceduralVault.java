package org.cland.alice.memory.vault;

import java.util.List;
import org.cland.alice.memory.agent.Context;
import org.cland.alice.memory.core.SOP;

/**
 * 程序记忆（Procedural Memory）Vault 接口。
 *
 * <p>负责存储"最佳实践"和工具使用 Schema（SOP），支持模式匹配/语义路由，将上下文路由到匹配的 SOP。
 */
public interface ProceduralVault {

  /**
   * 注册/更新一个 SOP。如果 sopId 已存在，则更新之。
   *
   * @param sop SOP 条目
   */
  void register(SOP sop);

  /**
   * 批量注册 SOP。
   *
   * @param sopList SOP 列表
   */
  void registerAll(List<SOP> sopList);

  /**
   * 根据上下文匹配最相关的 SOP。
   *
   * @param ctx 查询上下文
   * @return 按匹配度降序排列的 SOP 列表
   */
  List<SOP> match(Context ctx);

  /**
   * 根据工具名精确查找 SOP。
   *
   * @param toolName 工具名
   * @return 匹配的 SOP 列表
   */
  List<SOP> findByTool(String toolName);

  /**
   * 根据 SOP ID 获取 SOP。
   *
   * @param sopId SOP ID
   * @return SOP，或 null
   */
  SOP getById(String sopId);

  /**
   * 获取所有已注册的 SOP。
   *
   * @return 所有 SOP 列表
   */
  List<SOP> getAll();

  /**
   * 获取 SOP 数量。
   *
   * @return SOP 数量
   */
  int count();

  /**
   * 根据 SOP ID 删除一个 SOP。
   *
   * @param sopId SOP ID
   * @return 是否成功删除
   */
  boolean remove(String sopId);

  /** 清除所有 SOP。 */
  void clearAll();
}
