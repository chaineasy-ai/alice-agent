package org.cland.alice.memory.vault;

import java.util.List;
import org.cland.alice.memory.core.Knowledge;

/**
 * 语义记忆（Semantic Memory）Vault 接口。
 *
 * <p>负责存储结构化/非结构化知识（如项目文档、技术手册），支持通过向量相似度进行语义检索。
 */
public interface SemanticVault {

  /**
   * 向指定 collection 存入一条知识。
   *
   * @param collection collection 名称
   * @param knowledge 知识条目
   */
  void store(String collection, Knowledge knowledge);

  /**
   * 向知识条目自带的 collection 存入一条知识。如果 collection 为 null 或空，则存入默认 collection（"_default"）。
   *
   * @param knowledge 知识条目
   */
  void store(Knowledge knowledge);

  /**
   * 批量存入知识。
   *
   * @param collection collection 名称
   * @param knowledgeList 知识条目列表
   */
  void storeAll(String collection, List<Knowledge> knowledgeList);

  /**
   * 在指定 collection 中检索与查询最相似的若干条知识。
   *
   * @param collection collection 名称
   * @param query 查询文本
   * @return 按相似度降序排列的知识列表
   */
  List<Knowledge> search(String collection, String query);

  /**
   * 在所有 collection 中检索（跨 collection 搜索）。
   *
   * @param query 查询文本
   * @return 按相似度降序排列的知识列表
   */
  List<Knowledge> searchAll(String query);

  /**
   * 获取指定 collection 中的所有知识。
   *
   * @param collection collection 名称
   * @return 知识列表
   */
  List<Knowledge> getAll(String collection);

  /**
   * 获取所有 collection 名称。
   *
   * @return collection 名称列表
   */
  List<String> getCollections();

  /**
   * 获取指定 collection 中的知识数量。
   *
   * @param collection collection 名称
   * @return 知识数量
   */
  int count(String collection);

  /**
   * 删除指定 collection 中的一条知识。
   *
   * @param collection collection 名称
   * @param knowledgeId 知识 ID
   * @return 是否成功删除
   */
  boolean remove(String collection, String knowledgeId);

  /**
   * 删除整个 collection。
   *
   * @param collection collection 名称
   */
  void removeCollection(String collection);

  /** 清除所有知识。 */
  void clearAll();
}
