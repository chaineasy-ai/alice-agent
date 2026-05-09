package org.cland.alice.memory;

/**
 * 记忆存储后端的抽象接口。
 *
 * <p>对应设计文档中 VaultController 的 StorageBackend 引用。 支持最基础的键值存取，具体实现可以是内存、Redis、PostgreSQL 等。
 */
public interface StorageBackend {

  /** 存储一个键值对。 */
  void put(String key, byte[] value);

  /**
   * 读取一个键的值。
   *
   * @return 如果键不存在则返回 null
   */
  byte[] get(String key);

  /** 删除一个键。 */
  void delete(String key);

  /** 检查键是否存在。 */
  boolean exists(String key);

  /** 清除所有数据（仅供测试 / 重置使用）。 */
  void clear();
}
