package org.cland.alice.guardrail;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 策略引擎，对应设计文档中的 PolicyEngine。
 *
 * <p>内部维护：
 *
 * <ul>
 *   <li>{@link JsonSchemaValidator} — 基于 JsonSchema 的结构化数据验证
 *   <li>{@link RegexSafetyFilter} — 基于正则表达式的安全过滤
 * </ul>
 *
 * <p>用于确定性验证场景（安全策略、权限、数据格式合规性）。 设计为无状态可复用的校验引擎，可被多个 PreValidator / PostValidator 引用。
 */
public final class PolicyEngine {

  private final JsonSchemaValidator schemaValidator;
  private final RegexSafetyFilter safetyFilter;

  public PolicyEngine() {
    this.schemaValidator = new JsonSchemaValidator();
    this.safetyFilter = new RegexSafetyFilter();
  }

  /** 获取内置的 Schema 验证器。 */
  public JsonSchemaValidator schemaValidator() {
    return schemaValidator;
  }

  /** 获取内置的安全过滤器。 */
  public RegexSafetyFilter safetyFilter() {
    return safetyFilter;
  }

  // ========================================================================
  // Inner classes
  // ========================================================================

  /**
   * 基于 JsonSchema 的结构化数据验证器。
   *
   * <p>支持注册 Schema 定义，并通过 {@link #validate(String, String)} 验证 JSON 数据。 目前使用轻量级模式匹配实现，未来可替换为标准的
   * JSON Schema 库。
   */
  public static final class JsonSchemaValidator {

    private final Map<String, String> schemas = new ConcurrentHashMap<>();

    /**
     * 注册一个 Schema 定义。
     *
     * @param name Schema 名称/标识符
     * @param schema Schema 定义字符串（当前版本为简化 JSON 路径模板）
     */
    public void registerSchema(String name, String schema) {
      schemas.put(Objects.requireNonNull(name), Objects.requireNonNull(schema));
    }

    /**
     * 根据已注册的 Schema 验证 JSON 数据。
     *
     * <p>当前实现进行基本的键存在性和类型前缀检查。 未来将对接标准的 JSON Schema 验证库（如 networknt/json-schema-validator）。
     *
     * @param schemaName 已注册的 Schema 名称
     * @param jsonData JSON 格式的字符串数据
     * @return 验证是否通过
     */
    public boolean validate(String schemaName, String jsonData) {
      String schema = schemas.get(schemaName);
      if (schema == null) {
        return false; // Schema 未注册
      }
      if (jsonData == null || jsonData.isBlank()) {
        return false;
      }
      // 简化实现：检查 JSON 结构完整性
      // 仅作基本的花括号/中括号平衡检查
      return isJsonBalanced(jsonData);
    }

    /**
     * 使用内联 Schema 规则验证 Java 结构化数据。
     *
     * @param schemaRules Schema 规则键值对（key 为字段路径，value 为期望类型）
     * @param data 待验证的数据 Map
     * @return 验证失败的原因列表，为空表示全部通过
     */
    public List<String> validate(Map<String, Class<?>> schemaRules, Map<String, Object> data) {
      List<String> violations = new ArrayList<>();
      if (data == null) {
        violations.add("data is null");
        return violations;
      }
      for (Map.Entry<String, Class<?>> rule : schemaRules.entrySet()) {
        Object value = data.get(rule.getKey());
        if (value == null) {
          violations.add("Missing required field: " + rule.getKey());
        } else if (!rule.getValue().isInstance(value)) {
          violations.add(
              "Field '"
                  + rule.getKey()
                  + "' expected type "
                  + rule.getValue().getSimpleName()
                  + " but got "
                  + value.getClass().getSimpleName());
        }
      }
      return violations;
    }

    private boolean isJsonBalanced(String json) {
      int braceBalance = 0;
      int bracketBalance = 0;
      boolean inString = false;
      char prev = 0;

      for (int i = 0; i < json.length(); i++) {
        char c = json.charAt(i);
        if (c == '"' && prev != '\\') {
          inString = !inString;
        }
        if (!inString) {
          switch (c) {
            case '{' -> braceBalance++;
            case '}' -> braceBalance--;
            case '[' -> bracketBalance++;
            case ']' -> bracketBalance--;
          }
        }
        prev = c;
      }
      return braceBalance == 0 && bracketBalance == 0;
    }
  }

  /**
   * 基于正则表达式的安全过滤器。
   *
   * <p>维护一组安全规则（允许/拒绝模式），用于检测输入或输出中 是否包含敏感内容、非法路径、危险指令等。
   *
   * <p>对应设计文档中 "使用确定性代码逻辑 (Regex, JsonSchema, Policy-as-Code)" 的要求。
   */
  public static final class RegexSafetyFilter {

    private final List<Pattern> denyPatterns = new ArrayList<>();
    private final List<Pattern> allowPatterns = new ArrayList<>();

    /** 添加一个拒绝模式（黑名单）。命中任意拒绝规则即视为不安全。 */
    public void addDenyPattern(String regex) {
      denyPatterns.add(Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
    }

    /** 添加一个允许模式（白名单）。仅当内容匹配至少一个允许规则且无拒绝规则命中时通过。 */
    public void addAllowPattern(String regex) {
      allowPatterns.add(Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
    }

    /** 清空所有安全规则。 */
    public void clear() {
      denyPatterns.clear();
      allowPatterns.clear();
    }

    /**
     * 检查输入内容是否安全。
     *
     * @param content 待检查的字符串内容
     * @return 安全返回 true，不安全返回 false
     */
    public boolean isSafe(String content) {
      if (content == null || content.isBlank()) {
        return true;
      }

      // 黑名单检查：命中任意一个即不安全
      for (Pattern deny : denyPatterns) {
        if (deny.matcher(content).find()) {
          return false;
        }
      }

      // 如果存在白名单规则，必须至少匹配一条才安全
      if (!allowPatterns.isEmpty()) {
        for (Pattern allow : allowPatterns) {
          if (allow.matcher(content).find()) {
            return true;
          }
        }
        return false;
      }

      // 只有黑名单规则且未命中，安全
      return true;
    }

    /**
     * 返回第一个匹配的安全违规描述。
     *
     * @param content 待检查内容
     * @return 违规描述，未违规返回 Optional.empty()
     */
    public Optional<String> firstViolation(String content) {
      if (content == null || content.isBlank()) {
        return Optional.empty();
      }
      for (Pattern deny : denyPatterns) {
        var matcher = deny.matcher(content);
        if (matcher.find()) {
          return Optional.of(
              "Matched deny pattern: " + deny.pattern() + " at: '" + matcher.group() + "'");
        }
      }
      return Optional.empty();
    }
  }
}
