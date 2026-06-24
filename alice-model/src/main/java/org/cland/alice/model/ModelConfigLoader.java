package org.cland.alice.model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 模型配置加载器，读取 {@code ~/.alice/model.json} 配置文件。
 *
 * <p>对应 {@code docs/alice-model/CONFIG.md} 配置说明文档：
 *
 * <ul>
 *   <li>解析 {@code language_models.openai_compatible} 下的所有提供商
 *   <li>展开 {@code ${ENV_VAR}} 环境变量引用
 *   <li>校验核心字段合法性
 *   <li>提供结构化数据供 {@link ModelProvider} 注册使用
 * </ul>
 */
public final class ModelConfigLoader {

  private static final Logger logger = LoggerFactory.getLogger(ModelConfigLoader.class);

  /** 默认配置文件路径：{@code $HOME/.alice/model.json} */
  private static final String DEFAULT_CONFIG_PATH = ".alice/model.json";

  private final Path configPath;

  /** 解析后的默认模型 ID */
  private String defaultModel;

  /** 解析后的提供商配置列表 */
  private List<ProviderConfig> providers;

  /** 原始 JSON 字符串 */
  private String rawJson;

  // ========== 构造 ==========

  public ModelConfigLoader() {
    this.configPath = Paths.get(System.getProperty("user.home")).resolve(DEFAULT_CONFIG_PATH);
  }

  public ModelConfigLoader(Path configPath) {
    this.configPath = configPath;
  }

  // ========== 加载 ==========

  /**
   * 加载并解析配置文件。
   *
   * @throws IOException 文件读取或解析失败
   * @throws ConfigValidationException 配置校验失败
   */
  public ModelConfigLoader load() throws IOException {
    if (!Files.exists(configPath)) {
      logger.warn("Model config file not found: {}", configPath.toAbsolutePath());
      this.defaultModel = null;
      this.providers = List.of();
      return this;
    }

    logger.info("Loading model config from: {}", configPath.toAbsolutePath());
    this.rawJson = Files.readString(configPath);

    // 解析默认模型（可选的顶级字段）
    this.defaultModel = extractStringField(rawJson, "default_model");
    if (this.defaultModel != null && !this.defaultModel.isBlank()) {
      logger.info("Default model from config: {}", this.defaultModel);
    }

    // 手动解析 JSON（纯环境无需 JSON 库依赖）
    this.providers = parseProviders(rawJson);

    logger.info("Loaded {} provider(s) from config", providers.size());
    return this;
  }

  // ========== 查询 ==========

  /** 获取配置中指定的默认模型 ID，未设置则返回 {@code null}。 */
  public String getDefaultModel() {
    return defaultModel;
  }

  /** 获取所有加载成功的提供商配置。 */
  public List<ProviderConfig> getProviders() {
    return providers != null ? Collections.unmodifiableList(providers) : List.of();
  }

  /** 按名称查找提供商配置。 */
  public ProviderConfig getProvider(String name) {
    if (providers == null) return null;
    for (ProviderConfig p : providers) {
      if (p.name().equals(name)) return p;
    }
    return null;
  }

  /** 获取配置文件原始内容（仅用于诊断/日志）。 */
  public String getRawJson() {
    return rawJson;
  }

  // ========== 注册到 ModelProvider ==========

  /**
   * 便捷方法：将所有加载的提供商注册到 {@link ModelProvider}。
   *
   * <p>注意：此方法需要根据提供商类型创建对应的 {@link ModelSupplier} 实现。 目前仅支持 {@code openai_compatible} 类型， 所有提供商（包括
   * deepseek）都使用 {@link org.cland.alice.model.supplier.OpenAiSupplier}， 因为 DeepSeek API 与 OpenAI
   * Chat Completion 协议完全兼容。
   *
   * @param provider ModelProvider 单例
   */
  public void registerTo(ModelProvider provider) {
    if (providers == null || providers.isEmpty()) return;

    for (ProviderConfig p : providers) {
      ModelSupplier supplier = createSupplier(p);
      if (supplier != null) {
        provider.registerSupplier(supplier);
        logger.info("Registered supplier: {} ({} models)", p.name(), p.models().size());
      }

      // 注册每个模型的 Model 元数据
      for (ModelConfig m : p.models()) {
        Model model = buildModel(p, m);
        provider.registerModel(model);
        logger.debug("Registered model: {} via supplier {}", m.name(), p.name());
      }
    }
  }

  /** 根据配置创建对应的 ModelSupplier 实例。 */
  private ModelSupplier createSupplier(ProviderConfig p) {
    String name = p.name();
    String apiKey = p.apiKey();
    String baseUrl = p.apiUrl();

    // 按提供商名称分派
    return switch (name) {
      case "openai" ->
          new org.cland.alice.model.supplier.OpenAiSupplier(
              name, apiKey != null ? apiKey : "", baseUrl);
      case "gemma4", "gemma" -> new org.cland.alice.model.supplier.Gemma4Supplier(name, baseUrl);
      default ->
          // 默认用 OpenAI 兼容实现
          new org.cland.alice.model.supplier.OpenAiSupplier(
              name, apiKey != null ? apiKey : "", baseUrl);
    };
  }

  /** 从配置构建 Model 元数据对象。 */
  private Model buildModel(ProviderConfig p, ModelConfig m) {
    Model.Capability cap = toModelCapability(m.capabilities());
    return Model.builder()
        .modelId(m.name())
        .supplierName(p.name())
        .capability(cap)
        .pricing(Model.Pricing.ZERO)
        .build();
  }

  /** 将 capabilities 字典映射为 Model.Capability 位掩码。 */
  private static Model.Capability toModelCapability(Map<String, Boolean> caps) {
    int mask = 0;
    if (Boolean.TRUE.equals(caps.get("tools"))) mask |= 1; // FUNCTION_CALL
    if (Boolean.TRUE.equals(caps.get("images"))) mask |= 1 << 1; // VISION
    if (Boolean.TRUE.equals(caps.get("parallel_tool_calls"))) mask |= 1; // implies FC
    // STREAMING is always assumed available for API-based models
    mask |= 1 << 2; // STREAMING
    return Model.Capability.fromMask(mask);
  }

  // ========== JSON 解析 ==========

  /** 从 JSON 字符串中解析提供商配置列表。 */
  private List<ProviderConfig> parseProviders(String json) {
    // 定位 "language_models": { "openai_compatible": { ... } }
    String langModelsSection = extractObjectField(json, "language_models");
    if (langModelsSection == null) {
      logger.warn("No 'language_models' section found in config");
      return List.of();
    }

    String openaiCompatSection = extractObjectField(langModelsSection, "openai_compatible");
    if (openaiCompatSection == null) {
      logger.warn("No 'openai_compatible' section found in config");
      return List.of();
    }

    // 解析每个提供商
    List<ProviderConfig> result = new ArrayList<>();
    List<NameValue> providers = extractTopLevelFields(openaiCompatSection);
    for (NameValue p : providers) {
      if (p.value() == null || p.value().isBlank()) continue;
      try {
        ProviderConfig pc = parseProviderConfig(p.name(), p.value());
        if (pc != null) {
          result.add(pc);
        }
      } catch (Exception e) {
        logger.warn("Failed to parse provider '{}': {}", p.name(), e.getMessage());
      }
    }

    return result;
  }

  /** 解析单个提供商配置对象 JSON。 */
  private ProviderConfig parseProviderConfig(String name, String json) {
    String apiUrl = extractStringField(json, "base_url");
    if (apiUrl == null) {
      logger.warn("Provider '{}' missing 'base_url', skipping", name);
      return null;
    }

    // 校验 base_url 格式
    if (!apiUrl.startsWith("http://") && !apiUrl.startsWith("https://")) {
      logger.warn(
          "Provider '{}' has invalid base_url (must start with http:// or https://): {}",
          name,
          apiUrl);
      return null;
    }

    String apiKey = extractStringField(json, "api_key");
    if (apiKey != null) {
      apiKey = expandEnvVar(apiKey);
    }

    // 解析 available_models 数组
    String modelsJson = extractArrayField(json, "available_models");
    List<ModelConfig> models = parseModels(name, modelsJson);
    if (models.isEmpty()) {
      logger.warn("Provider '{}' has no valid models in 'available_models'", name);
      return null;
    }

    return new ProviderConfig(name, apiUrl, apiKey, models);
  }

  /** 解析 available_models 数组 JSON。 */
  private List<ModelConfig> parseModels(String providerName, String json) {
    if (json == null || json.isBlank()) return List.of();

    List<ModelConfig> result = new ArrayList<>();
    List<String> elements = extractArrayElements(json);
    for (String elem : elements) {
      if (elem == null || elem.isBlank()) continue;
      try {
        ModelConfig m = parseModelConfig(elem);
        if (m != null) {
          result.add(m);
        }
      } catch (Exception e) {
        logger.warn("Failed to parse model in provider '{}': {}", providerName, e.getMessage());
      }
    }
    return result;
  }

  /** 解析单个模型配置对象 JSON。 */
  private ModelConfig parseModelConfig(String json) {
    String name = extractStringField(json, "name");
    if (name == null || name.isBlank()) return null;

    // max_tokens, max_output_tokens, max_completion_tokens (可选，有默认值)
    int maxTokens = extractIntField(json, "max_tokens", 4096);
    int maxOutputTokens = extractIntField(json, "max_output_tokens", 2048);
    int maxCompletionTokens = extractIntField(json, "max_completion_tokens", maxTokens);

    // 校验规则：max_tokens >= max_output_tokens
    if (maxTokens < maxOutputTokens) {
      logger.warn(
          "Model '{}': max_tokens ({}) < max_output_tokens ({}), adjusting max_tokens",
          name,
          maxTokens,
          maxOutputTokens);
      maxTokens = maxOutputTokens;
    }

    // 解析 capabilities
    String capsJson = extractObjectField(json, "capabilities");
    Map<String, Boolean> capabilities = parseCapabilities(capsJson);

    return new ModelConfig(name, maxTokens, maxOutputTokens, maxCompletionTokens, capabilities);
  }

  /** 解析 capabilities 对象。 */
  private Map<String, Boolean> parseCapabilities(String json) {
    if (json == null || json.isBlank()) return Map.of();

    Map<String, Boolean> caps = new LinkedHashMap<>();
    caps.put("tools", extractBooleanField(json, "tools", false));
    caps.put("images", extractBooleanField(json, "images", false));
    caps.put("parallel_tool_calls", extractBooleanField(json, "parallel_tool_calls", false));
    caps.put("prompt_cache_key", extractBooleanField(json, "prompt_cache_key", false));
    caps.put("chat_completions", extractBooleanField(json, "chat_completions", true));
    return caps;
  }

  // ========== 环境变量展开 ==========

  /** 展开 ${ENV_VAR} 格式的环境变量引用。 */
  static String expandEnvVar(String value) {
    if (value == null) return null;
    if (value.startsWith("${") && value.endsWith("}")) {
      String envVar = value.substring(2, value.length() - 1);
      String envValue = System.getenv(envVar);
      if (envValue != null && !envValue.isEmpty()) {
        return envValue;
      }
      logger.warn("Environment variable '{}' not set or empty, using literal value", envVar);
    }
    return value;
  }

  // ========== 手写简易 JSON 工具方法 ==========

  /** 从 JSON 对象中提取指定字段的字符串值。 查找 "field": "value" 模式。 */
  private static String extractStringField(String json, String field) {
    if (json == null) return null;
    String search = "\"" + field + "\":";
    int idx = json.indexOf(search);
    if (idx < 0) return null;

    idx += search.length();
    while (idx < json.length() && json.charAt(idx) == ' ') idx++;
    if (idx >= json.length()) return null;

    if (json.charAt(idx) == '"') {
      // 字符串值
      StringBuilder sb = new StringBuilder();
      idx++;
      while (idx < json.length()) {
        char c = json.charAt(idx);
        if (c == '\\') {
          if (idx + 1 < json.length()) {
            sb.append(json.charAt(idx + 1));
            idx += 2;
          } else {
            break;
          }
        } else if (c == '"') {
          break;
        } else {
          sb.append(c);
          idx++;
        }
      }
      return sb.toString();
    }
    return null;
  }

  /** 从 JSON 对象中提取指定字段的整数值。 */
  private static int extractIntField(String json, String field, int defaultValue) {
    String search = "\"" + field + "\":";
    int idx = json.indexOf(search);
    if (idx < 0) return defaultValue;

    idx += search.length();
    while (idx < json.length() && json.charAt(idx) == ' ') idx++;
    if (idx >= json.length()) return defaultValue;

    StringBuilder sb = new StringBuilder();
    while (idx < json.length()) {
      char c = json.charAt(idx);
      if (c == ',' || c == '}' || c == ']') break;
      if (c >= '0' && c <= '9') {
        sb.append(c);
      } else if (c == '-' && sb.isEmpty()) {
        sb.append(c);
      }
      idx++;
    }
    if (sb.isEmpty()) return defaultValue;
    try {
      return Integer.parseInt(sb.toString());
    } catch (NumberFormatException e) {
      return defaultValue;
    }
  }

  /** 从 JSON 对象中提取指定字段的布尔值。 */
  private static boolean extractBooleanField(String json, String field, boolean defaultValue) {
    if (json == null) return defaultValue;
    String search = "\"" + field + "\":";
    int idx = json.indexOf(search);
    if (idx < 0) return defaultValue;

    idx += search.length();
    while (idx < json.length() && json.charAt(idx) == ' ') idx++;
    if (idx >= json.length()) return defaultValue;

    if (idx + 4 <= json.length() && json.substring(idx, idx + 4).equals("true")) return true;
    if (idx + 5 <= json.length() && json.substring(idx, idx + 5).equals("false")) return false;

    return defaultValue;
  }

  /** 从 JSON 对象中提取指定字段的对象值（嵌套的大括号内容）。 返回最外层匹配的大括号内容，包括内部所有嵌套。 */
  private static String extractObjectField(String json, String field) {
    if (json == null) return null;
    String search = "\"" + field + "\":";
    int idx = json.indexOf(search);
    if (idx < 0) return null;

    idx += search.length();
    while (idx < json.length() && json.charAt(idx) == ' ') idx++;
    if (idx >= json.length()) return null;

    if (json.charAt(idx) == '{') {
      return extractBracketedContent(json, idx, '{', '}');
    }
    return null;
  }

  /** 从 JSON 对象中提取指定字段的数组值（中括号内容）。 返回最外层匹配的中括号内容，包括内部所有嵌套。 */
  private static String extractArrayField(String json, String field) {
    if (json == null) return null;
    String search = "\"" + field + "\":";
    int idx = json.indexOf(search);
    if (idx < 0) return null;

    idx += search.length();
    while (idx < json.length() && json.charAt(idx) == ' ') idx++;
    if (idx >= json.length()) return null;

    if (json.charAt(idx) == '[') {
      return extractBracketedContent(json, idx, '[', ']');
    }
    return null;
  }

  /** 提取从指定索引开始的括号内容，支持嵌套。 startIdx 应指向开括号字符。 */
  private static String extractBracketedContent(String json, int startIdx, char open, char close) {
    int depth = 0;
    int start = startIdx;
    boolean inString = false;
    for (int i = startIdx; i < json.length(); i++) {
      char c = json.charAt(i);
      if (inString) {
        if (c == '\\') {
          i++; // skip escaped char
          continue;
        }
        if (c == '"') {
          inString = false;
        }
        continue;
      }
      if (c == '"') {
        inString = true;
        continue;
      }
      if (c == open) {
        if (depth == 0) start = i;
        depth++;
      } else if (c == close) {
        depth--;
        if (depth == 0) {
          return json.substring(start, i + 1);
        }
      }
    }
    return null;
  }

  /** 提取数组的各个元素（字符串或对象），返回 list of JSON 片段。 仅处理对象和字符串元素。 */
  private static List<String> extractArrayElements(String json) {
    if (json == null || json.isBlank()) return List.of();
    json = json.trim();
    if (!json.startsWith("[") || !json.endsWith("]")) return List.of();

    // 去掉外层 []
    String inner = json.substring(1, json.length() - 1).trim();
    if (inner.isEmpty()) return List.of();

    List<String> result = new ArrayList<>();
    int depth = 0;
    boolean inString = false;
    int start = -1;

    for (int i = 0; i < inner.length(); i++) {
      char c = inner.charAt(i);
      if (inString) {
        if (c == '\\') {
          i++;
          continue;
        }
        if (c == '"') inString = false;
        continue;
      }
      if (c == '"') {
        inString = true;
        if (start < 0) start = i;
        continue;
      }
      if (c == '{' || c == '[') {
        if (depth == 0 && start < 0) start = i;
        depth++;
        continue;
      }
      if (c == '}' || c == ']') {
        depth--;
        if (depth == 0) {
          result.add(inner.substring(start, i + 1));
          start = -1;
        }
        continue;
      }
      if (c == ',' && depth == 0) {
        if (start >= 0) {
          result.add(inner.substring(start, i));
          start = -1;
        }
        continue;
      }
      // 非结构化字符（不在对象/字符串内），忽略
    }
    // 最后一个元素
    if (start >= 0) {
      result.add(inner.substring(start));
    }

    return result;
  }

  /** 从 JSON 对象中提取顶级字段的键值对列表。 返回字段名和其对应的值片段（字符串、对象或数组）。 */
  private static List<NameValue> extractTopLevelFields(String json) {
    if (json == null || json.isBlank()) return List.of();
    json = json.trim();
    if (!json.startsWith("{") || !json.endsWith("}")) return List.of();

    String inner = json.substring(1, json.length() - 1).trim();
    if (inner.isEmpty()) return List.of();

    List<NameValue> result = new ArrayList<>();
    int i = 0;
    while (i < inner.length()) {
      // 跳过空白
      while (i < inner.length() && inner.charAt(i) <= ' ') i++;
      if (i >= inner.length()) break;

      // 读取字段名（以 " 开始）
      if (inner.charAt(i) != '"') break;
      int nameStart = i + 1;
      i++;
      while (i < inner.length()) {
        if (inner.charAt(i) == '\\') {
          i += 2;
          continue;
        }
        if (inner.charAt(i) == '"') break;
        i++;
      }
      String fieldName = inner.substring(nameStart, i);
      i++; // skip closing quote

      // 跳过空白和 :
      while (i < inner.length() && (inner.charAt(i) <= ' ' || inner.charAt(i) == ':')) i++;
      if (i >= inner.length()) break;

      // 读取值
      char valStart = inner.charAt(i);
      if (valStart == '"') {
        // 字符串值
        int valBegin = i;
        i++;
        while (i < inner.length()) {
          if (inner.charAt(i) == '\\') {
            i += 2;
            continue;
          }
          if (inner.charAt(i) == '"') {
            i++;
            break;
          }
          i++;
        }
        result.add(new NameValue(fieldName, inner.substring(valBegin, i)));
      } else if (valStart == '{' || valStart == '[') {
        char close = (valStart == '{') ? '}' : ']';
        int valBegin = i;
        int depth = 1;
        i++;
        while (i < inner.length() && depth > 0) {
          if (inner.charAt(i) == '"') {
            i++;
            while (i < inner.length()) {
              if (inner.charAt(i) == '\\') {
                i += 2;
                continue;
              }
              if (inner.charAt(i) == '"') break;
              i++;
            }
          } else if (inner.charAt(i) == valStart) {
            depth++;
          } else if (inner.charAt(i) == close) {
            depth--;
          }
          i++;
        }
        result.add(new NameValue(fieldName, inner.substring(valBegin, i)));
      } else {
        // 原始值 (number, boolean, null)
        int valBegin = i;
        while (i < inner.length() && inner.charAt(i) != ',') i++;
        result.add(new NameValue(fieldName, inner.substring(valBegin, i).trim()));
      }

      // 跳过 ,
      while (i < inner.length() && (inner.charAt(i) <= ' ' || inner.charAt(i) == ',')) i++;
    }

    return result;
  }

  // ========== 内部类型 ==========

  /** 提供商配置。 */
  public record ProviderConfig(
      String name, String apiUrl, String apiKey, List<ModelConfig> models) {
    public ProviderConfig {
      name = name != null ? name : "";
      apiUrl = apiUrl != null ? apiUrl : "";
      models = models != null ? List.copyOf(models) : List.of();
    }
  }

  /** 模型配置。 */
  public record ModelConfig(
      String name,
      int maxTokens,
      int maxOutputTokens,
      int maxCompletionTokens,
      Map<String, Boolean> capabilities) {
    public ModelConfig {
      name = name != null ? name : "";
      capabilities = capabilities != null ? Map.copyOf(capabilities) : Map.of();
      if (maxCompletionTokens < maxTokens) {
        maxCompletionTokens = maxTokens;
      }
    }
  }

  /** 键值对辅助类型。 */
  private record NameValue(String name, String value) {}

  // ========== 异常 ==========

  /** 配置校验异常。 */
  public static final class ConfigValidationException extends RuntimeException {
    public ConfigValidationException(String message) {
      super(message);
    }

    public ConfigValidationException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
