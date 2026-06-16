/*
 * Alice Agent — AliceConfigStore
 *
 * Persistent JSON config at ~/.alice/config.json.
 * Thread-safe, zero external dependencies beyond Jackson.
 */
package org.cland.alice.facade.cmd.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 持久化配置存储，读写 {@code ~/.alice/config.json}。
 *
 * <p>支持键值对的 get/set/del，线程安全。首次写入时自动创建目录和文件。
 *
 * <p>键名使用点分隔路径：
 *
 * <ul>
 *   <li>{@code providers.openai.api_key} → JSON 嵌套 {@code {"providers":{"openai":{"api_key":"..."}}}
 *   <li>{@code default.timeout} → 根级扁平键 {@code {"default_timeout": 180}}
 * </ul>
 *
 * <p>点分隔路径中，第一段是顶级 JSON key，后续段嵌套子对象。单段键名中的点转换为下划线。
 */
public final class AliceConfigStore {

  private static final Logger logger = LoggerFactory.getLogger(AliceConfigStore.class);

  /** 默认配置目录 ~/.alice */
  private static final String CONFIG_DIR = ".alice";

  /** 默认配置文件名 */
  private static final String CONFIG_FILE = "config.json";

  private static final ObjectMapper MAPPER =
      new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

  private final Path configPath;
  private volatile Map<String, Object> cache;

  /** 使用默认路径 {@code ~/.alice/config.json} 创建实例。 */
  public AliceConfigStore() {
    this(Path.of(System.getProperty("user.home"), CONFIG_DIR, CONFIG_FILE));
  }

  /**
   * 使用自定义路径创建实例（主要用于测试）。
   *
   * @param configPath 配置文件的完整路径
   */
  public AliceConfigStore(Path configPath) {
    this.configPath = Objects.requireNonNull(configPath, "configPath must not be null");
    this.cache = loadInternal();
  }

  // ========================================================================
  // 公共 API
  // ========================================================================

  /**
   * 获取一个配置值。不存在的键返回 {@code null}。
   *
   * @param key 点分隔的键名（例如 {@code providers.openai.api_key}）
   * @return 字符串值，或 {@code null}
   */
  public synchronized String get(String key) {
    Object val = resolvePath(key);
    return val != null ? val.toString() : null;
  }

  /**
   * 设置一个配置值。持久化到磁盘。
   *
   * <p>多段键（如 {@code providers.openai.api_key}）按路径嵌套写入。 单段键（如 {@code
   * default.timeout}）中的点转换为下划线，仍然写入根级。
   *
   * @param key 点分隔的键名
   * @param value 字符串值
   */
  public synchronized void set(String key, String value) {
    List<String> segments = splitKey(key);
    if (segments.size() >= 2) {
      // 多段键 → 嵌套路径: providers.openai.api_key → {providers: {openai: {api_key: val}}}
      putNested(cache, segments, value);
    } else {
      // 单段键或有下划线 → 扁平: default.timeout → default_timeout
      cache.put(normalizeKey(key), value);
    }
    saveInternal();
  }

  /**
   * 删除一个配置键。
   *
   * @param key 点分隔的键名
   * @return 如果该键存在并被删除则返回 {@code true}
   */
  public synchronized boolean delete(String key) {
    boolean removed = removePath(key);
    if (removed) {
      saveInternal();
      return true;
    }
    return false;
  }

  /**
   * 获取所有配置项的不可修改视图。
   *
   * @return 键-值映射（嵌套结构保留原样）
   */
  public synchronized Map<String, Object> getAll() {
    return deepCopy(cache);
  }

  // ========================================================================
  // 路径解析
  // ========================================================================

  /**
   * 将点分隔键名解析为路径段列表。
   *
   * <p>规则：
   *
   * <ul>
   *   <li>点分隔路径（如 {@code providers.openai.api_key}）→ {@code ["providers", "openai", "api_key"]}
   *   <li>单段键名（如 {@code default.timeout}）→ {@code ["default_timeout"]}（下划线代替点）
   *   <li>纯单段（如 {@code max_iterations}）→ {@code ["max_iterations"]}
   * </ul>
   */
  static List<String> splitKey(String key) {
    String[] parts = key.split("\\.");
    // 如果只有一段，或者所有段都是 provider/{provider} 类型 → 扁平
    // 启发式：2段或以上且不是公认的单段点分键 → 走嵌套
    if (parts.length == 1) {
      return List.of(parts[0]);
    }
    if (parts.length == 2) {
      // 形如 default.timeout、openai.api_key
      // 2段键：如果第一段是已知的 "根级命名空间" 则扁平化
      // 否则走嵌套
      // 根级命名空间列表:
      String first = parts[0];
      if (isFlatNamespace(first)) {
        return List.of(normalizeKey(key));
      }
    }
    // 3段+ 或者 2段且第一段不在扁平命名空间列表中
    // providers.openai.api_key → ["providers", "openai", "api_key"]
    return List.of(parts);
  }

  /** 判断是否为扁平命名空间（根级 key 使用下划线存储）。 */
  private static boolean isFlatNamespace(String first) {
    return "default".equals(first)
        || "openai".equals(first)
        || "anthropic".equals(first)
        || "agent".equals(first)
        || "action".equals(first);
  }

  /** 沿路径链解析值，返回 null 表示不存在。 */
  @SuppressWarnings("unchecked")
  private Object resolvePath(String key) {
    List<String> segments = splitKey(key);
    // 先尝试按路径走
    Map<String, Object> current = cache;
    for (int i = 0; i < segments.size(); i++) {
      Object val = current.get(segments.get(i));
      if (val == null) {
        return null;
      }
      if (i == segments.size() - 1) {
        return val;
      }
      if (val instanceof Map) {
        current = (Map<String, Object>) val;
      } else {
        return null; // 路径中断
      }
    }
    return null;
  }

  /** 沿路径删除值。返回 true 如果值存在且被删除。 */
  @SuppressWarnings("unchecked")
  private boolean removePath(String key) {
    List<String> segments = splitKey(key);
    if (segments.isEmpty()) return false;

    // 单段: 直接移除
    if (segments.size() == 1) {
      return cache.remove(segments.get(0)) != null;
    }

    // 多段: 走到父节点再移除
    Map<String, Object> current = cache;
    for (int i = 0; i < segments.size() - 1; i++) {
      Object val = current.get(segments.get(i));
      if (!(val instanceof Map)) return false;
      current = (Map<String, Object>) val;
    }
    return current.remove(segments.get(segments.size() - 1)) != null;
  }

  /**
   * 按路径段写入嵌套值。自动创建中间 Map。
   *
   * <pre>{@code
   * putNested({}, ["providers", "openai", "api_key"], "sk-xxx")
   * → {providers: {openai: {api_key: "sk-xxx"}}}
   * }</pre>
   */
  @SuppressWarnings("unchecked")
  static void putNested(Map<String, Object> map, List<String> segments, Object value) {
    Map<String, Object> current = map;
    for (int i = 0; i < segments.size() - 1; i++) {
      Object next = current.get(segments.get(i));
      if (next instanceof Map) {
        current = (Map<String, Object>) next;
      } else {
        Map<String, Object> child = new HashMap<>();
        current.put(segments.get(i), child);
        current = child;
      }
    }
    current.put(segments.get(segments.size() - 1), value);
  }

  /** 深拷贝一个 Map（递归）。 */
  @SuppressWarnings("unchecked")
  static Map<String, Object> deepCopy(Map<String, Object> source) {
    Map<String, Object> result = new HashMap<>();
    for (var entry : source.entrySet()) {
      Object val = entry.getValue();
      if (val instanceof Map) {
        result.put(entry.getKey(), deepCopy((Map<String, Object>) val));
      } else {
        result.put(entry.getKey(), val);
      }
    }
    return Map.copyOf(result);
  }

  // ========================================================================
  // 内部实现
  // ========================================================================

  /** 将 CLI 点分隔键名转换为 JSON 下划线键名（用于单段/扁平键）。 */
  static String normalizeKey(String key) {
    return key.replace('.', '_');
  }

  /** 重新加载配置（从磁盘刷新缓存）。 */
  synchronized void reload() {
    this.cache = loadInternal();
  }

  /** 加载配置（文件不存在或解析失败时返回空 Map）。 */
  @SuppressWarnings("unchecked")
  private Map<String, Object> loadInternal() {
    if (!Files.exists(configPath)) {
      logger.debug("Config file not found: {}", configPath);
      return new HashMap<>();
    }
    try {
      byte[] bytes = Files.readAllBytes(configPath);
      Map<String, Object> result = MAPPER.readValue(bytes, Map.class);
      logger.debug("Loaded config from {}", configPath);
      return result != null ? new HashMap<>(result) : new HashMap<>();
    } catch (IOException e) {
      logger.warn(
          "Failed to read config file {}, starting with empty config: {}",
          configPath,
          e.getMessage());
      return new HashMap<>();
    }
  }

  /** 保存配置到磁盘。自动创建目录。 */
  private void saveInternal() {
    try {
      Files.createDirectories(configPath.getParent());
      Path tmp = configPath.resolveSibling(configPath.getFileName() + ".tmp");
      MAPPER.writeValue(tmp.toFile(), cache);
      Files.move(
          tmp, configPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      logger.debug("Saved config to {}", configPath);
    } catch (IOException e) {
      logger.error("Failed to write config file {}", configPath, e);
      throw new RuntimeException("Failed to write config: " + e.getMessage(), e);
    }
  }
}
