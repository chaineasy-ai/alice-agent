package org.cland.alice.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 模型配置加载器，读取 {@code ~/.alice/model.json}。
 *
 * <p>使用 Jackson 解析。格式：
 *
 * <pre>{@code
 * {
 *   "default_model": { "provider":"...", "model":"...", "enable_thinking":true, "reasoning_effort":"high" },
 *   "planner": { "instruction_model_id":"...", "reasoning_model_id":"...",
 *       "instruction": { "enable_thinking":false, "reasoning_effort":"low" },
 *       "reasoning":   { "enable_thinking":true,  "reasoning_effort":"high" } },
 *   "providers": {
 *     "deepseek": {
 *       "base_url": "...", "api_key": "${DEEPSEEK_API_KEY}",
 *       "available_models": [
 *         { "name":"deepseek-v4-flash", "model":"deepseek-v4-flash",
 *           "max_tokens":131072, "max_output_tokens":32000,
 *           "capabilities": { "tools":true, "images":false, ... } }
 *       ]
 *     }
 *   }
 * }</pre>
 */
public final class ModelConfigLoader {

  private static final Logger logger = LoggerFactory.getLogger(ModelConfigLoader.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String DEFAULT_CONFIG_PATH = ".alice/model.json";

  private final Path configPath;
  private JsonNode root;

  public ModelConfigLoader() {
    this.configPath = Paths.get(System.getProperty("user.home")).resolve(DEFAULT_CONFIG_PATH);
  }

  public ModelConfigLoader(Path configPath) {
    this.configPath = configPath;
  }

  // ========== 加载 ==========

  public ModelConfigLoader load() throws IOException {
    if (!Files.exists(configPath)) {
      logger.warn("Config not found: {}", configPath.toAbsolutePath());
      return this;
    }
    logger.info("Loading config from: {}", configPath.toAbsolutePath());
    this.root = MAPPER.readTree(configPath.toFile());
    return this;
  }

  // ========== 查询 ==========

  /** 默认模型 ID（从 {@code default_model.model}），未配置返回 {@code null}。 */
  public String getDefaultModel() {
    JsonNode dm = node("default_model");
    return dm != null ? text(dm, "model") : null;
  }

  /** 默认模型配置对象。 */
  public DefaultModelConfig getDefaultModelConfig() {
    JsonNode dm = node("default_model");
    if (dm == null) return null;
    String provider = text(dm, "provider");
    String model = text(dm, "model");
    if (provider == null || model == null) return null;
    boolean thinking = bool(dm, "enable_thinking", true);
    String effort = text(dm, "reasoning_effort");
    if (effort == null) effort = thinking ? "high" : "low";
    return new DefaultModelConfig(provider, model, thinking, effort);
  }

  /** 规划器配置。 */
  public PlannerConfig getPlannerConfig() {
    JsonNode p = node("planner");
    if (p == null) return null;
    String instrId = text(p, "instruction_model_id");
    String reasId = text(p, "reasoning_model_id");

    JsonNode instr = p.get("instruction");
    boolean instrThinking = instr != null ? bool(instr, "enable_thinking", false) : false;
    String instrEffort = instr != null ? text(instr, "reasoning_effort", "low") : "low";

    JsonNode reas = p.get("reasoning");
    boolean reasThinking = reas != null ? bool(reas, "enable_thinking", true) : true;
    String reasEffort = reas != null ? text(reas, "reasoning_effort", "high") : "high";

    return new PlannerConfig(
        instrId,
        reasId,
        new PathThinkingConfig(instrThinking, instrEffort),
        new PathThinkingConfig(reasThinking, reasEffort));
  }

  /** 获取所有提供商名称 → 凭据映射。 每个 {@link ProviderEntry} 包含 base_url、api_key 和 available_models 列表。 */
  public Map<String, ProviderEntry> getProviders() {
    JsonNode prov = node("providers");
    if (prov == null || !prov.isObject()) return Map.of();
    Map<String, ProviderEntry> result = new LinkedHashMap<>();
    for (var it = prov.fields(); it.hasNext(); ) {
      var entry = it.next();
      String name = entry.getKey();
      JsonNode val = entry.getValue();
      if (!val.isObject()) continue;
      String baseUrl = text(val, "base_url");
      if (baseUrl == null) continue;
      String apiKey = text(val, "api_key");
      if (apiKey != null) apiKey = expandEnvVar(apiKey);
      List<AvailableModel> models = parseAvailableModels(val.get("available_models"));
      result.put(name, new ProviderEntry(baseUrl, apiKey, models));
    }
    return result;
  }

  /** 获取模型池条目（由所有 provider 的 available_models 聚合），按 name 索引。 */
  public Map<String, AvailableModel> getModelPool() {
    Map<String, AvailableModel> pool = new LinkedHashMap<>();
    for (ProviderEntry pe : getProviders().values()) {
      for (AvailableModel m : pe.availableModels()) {
        pool.put(m.name(), m);
      }
    }
    return Collections.unmodifiableMap(pool);
  }

  /** 按 name 查找模型池条目。 */
  public AvailableModel getModelPoolEntry(String name) {
    return getModelPool().get(name);
  }

  // ========== 注册 ==========

  /** 将所有提供商注册到 {@link ModelProvider}。 */
  public void registerTo(ModelProvider provider) {
    for (var entry : getProviders().entrySet()) {
      String providerName = entry.getKey();
      ProviderEntry pe = entry.getValue();

      ModelSupplier s = createSupplier(providerName, pe.apiKey(), pe.baseUrl());
      if (s != null) {
        provider.registerSupplier(s);
        logger.info(
            "Registered supplier: {} ({} models)", providerName, pe.availableModels().size());
      }

      for (AvailableModel m : pe.availableModels()) {
        Model model = buildModel(providerName, m);
        provider.registerModel(model);
        logger.debug("Registered model: {} via {}", m.name(), providerName);
      }
    }
  }

  private ModelSupplier createSupplier(String name, String apiKey, String baseUrl) {
    return switch (name) {
      case "openai" ->
          new org.cland.alice.model.supplier.OpenAiSupplier(
              name, apiKey != null ? apiKey : "", baseUrl);
      case "gemma4", "gemma" -> new org.cland.alice.model.supplier.Gemma4Supplier(name, baseUrl);
      default ->
          new org.cland.alice.model.supplier.OpenAiSupplier(
              name, apiKey != null ? apiKey : "", baseUrl);
    };
  }

  private Model buildModel(String supplierName, AvailableModel m) {
    int mask = 0;
    Map<String, Boolean> caps = m.capabilities();
    if (Boolean.TRUE.equals(caps.get("tools"))) mask |= 1;
    if (Boolean.TRUE.equals(caps.get("images"))) mask |= 1 << 1;
    mask |= 1 << 2; // STREAMING
    return Model.builder()
        .modelId(m.name())
        .supplierName(supplierName)
        .capability(Model.Capability.fromMask(mask))
        .pricing(Model.Pricing.ZERO)
        .build();
  }

  // ========== 辅助解析 ==========

  private List<AvailableModel> parseAvailableModels(JsonNode arr) {
    if (arr == null || !arr.isArray()) return List.of();
    List<AvailableModel> result = new ArrayList<>();
    for (JsonNode m : arr) {
      String name = text(m, "name");
      String model = text(m, "model");
      if (name == null) continue;
      if (model == null) model = name;
      int maxTokens = m.has("max_tokens") ? m.get("max_tokens").asInt(4096) : 4096;
      int maxOutput =
          m.has("max_output_tokens") ? m.get("max_output_tokens").asInt(maxTokens) : maxTokens;
      if (maxTokens < maxOutput) {
        logger.warn("Model '{}': max_tokens < max_output_tokens, adjusting", name);
        maxTokens = maxOutput;
      }
      Map<String, Boolean> caps = parseCapabilities(m.get("capabilities"));
      result.add(new AvailableModel(name, model, maxTokens, maxOutput, caps));
    }
    return result;
  }

  private Map<String, Boolean> parseCapabilities(JsonNode caps) {
    if (caps == null || !caps.isObject()) return Map.of();
    Map<String, Boolean> result = new LinkedHashMap<>();
    result.put("tools", caps.path("tools").asBoolean(false));
    result.put("images", caps.path("images").asBoolean(false));
    result.put("parallel_tool_calls", caps.path("parallel_tool_calls").asBoolean(false));
    result.put("prompt_cache_key", caps.path("prompt_cache_key").asBoolean(false));
    result.put("chat_completions", caps.path("chat_completions").asBoolean(true));
    return result;
  }

  // ========== Jackson 便捷方法 ==========

  private JsonNode node(String field) {
    return root != null ? root.get(field) : null;
  }

  private static String text(JsonNode node, String field) {
    JsonNode v = node.get(field);
    return v != null && v.isTextual() ? v.asText() : null;
  }

  private static String text(JsonNode node, String field, String def) {
    JsonNode v = node.get(field);
    return v != null && v.isTextual() ? v.asText() : def;
  }

  private static boolean bool(JsonNode node, String field, boolean def) {
    JsonNode v = node.get(field);
    return v != null && v.isBoolean() ? v.asBoolean() : def;
  }

  // ========== 环境变量展开 ==========

  static String expandEnvVar(String value) {
    if (value == null) return null;
    if (value.startsWith("${") && value.endsWith("}")) {
      String var = value.substring(2, value.length() - 1);
      String env = System.getenv(var);
      if (env != null && !env.isEmpty()) return env;
      logger.warn("Env var '{}' not set", var);
    }
    return value;
  }

  // ========== 内部类型 ==========

  public record DefaultModelConfig(
      String provider, String model, boolean enableThinking, String reasoningEffort) {
    public DefaultModelConfig {
      provider = provider != null ? provider : "";
      model = model != null ? model : "";
      reasoningEffort =
          reasoningEffort != null ? reasoningEffort : (enableThinking ? "high" : "low");
    }
  }

  public record PlannerConfig(
      String instructionModelId,
      String reasoningModelId,
      PathThinkingConfig instruction,
      PathThinkingConfig reasoning) {}

  public record PathThinkingConfig(boolean enableThinking, String reasoningEffort) {
    public PathThinkingConfig {
      reasoningEffort =
          reasoningEffort != null ? reasoningEffort : (enableThinking ? "high" : "low");
    }
  }

  /** 提供商条目：凭据 + 可用模型列表。 */
  public record ProviderEntry(String baseUrl, String apiKey, List<AvailableModel> availableModels) {
    public ProviderEntry {
      baseUrl = baseUrl != null ? baseUrl : "";
      availableModels = availableModels != null ? List.copyOf(availableModels) : List.of();
    }
  }

  /** 可用模型条目（对应 available_models[] 中的每个元素）。 */
  public record AvailableModel(
      String name,
      String model,
      int maxTokens,
      int maxOutputTokens,
      Map<String, Boolean> capabilities) {
    public AvailableModel {
      name = name != null ? name : "";
      model = model != null ? model : name;
      capabilities = capabilities != null ? Map.copyOf(capabilities) : Map.of();
    }
  }

  public static final class ConfigValidationException extends RuntimeException {
    public ConfigValidationException(String message) {
      super(message);
    }

    public ConfigValidationException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
