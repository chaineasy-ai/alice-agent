package org.cland.alice.memory.storage;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于内存的 {@link StorageBackend} 实现。
 *
 * <p>用于开发和测试阶段，生产环境应替换为 Redis / PostgreSQL 实现。
 */
public final class InMemoryStorageBackend implements StorageBackend {

  private final Map<String, byte[]> store = new ConcurrentHashMap<>();

  @Override
  public void put(String key, byte[] value) {
    store.put(key, value);
  }

  @Override
  public byte[] get(String key) {
    return store.get(key);
  }

  @Override
  public void delete(String key) {
    store.remove(key);
  }

  @Override
  public boolean exists(String key) {
    return store.containsKey(key);
  }

  @Override
  public void clear() {
    store.clear();
  }

  public int size() {
    return store.size();
  }

  @Override
  public String toString() {
    return "InMemoryStorageBackend{entries=%d}".formatted(store.size());
  }
}
