package org.cland.alice.tool.gateway.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import java.lang.invoke.MethodHandle;
import java.util.Map;
import java.util.Objects;
import org.cland.alice.tool.gateway.annotation.RiskLevel;
import org.cland.alice.tool.gateway.annotation.ToolParam;

/**
 * 工具的完整元数据描述。
 *
 * <p>对应设计文档类图中的 {@code ToolMetadata}，将 Java 强类型方法 映射为 LLM 可理解的工具原语。包含：
 *
 * <ul>
 *   <li>工具名称与描述（供 Planner 选择工具）
 *   <li>JSON Schema 输入描述（供 LLM 生成正确参数）
 *   <li>{@link MethodHandle} 目标方法引用（供快速反射调用）
 *   <li>风险等级（决定沙箱策略）
 * </ul>
 */
public final class ToolMetadata {

  private final String name;
  private final String description;
  private final JsonNode inputSchema;
  private final MethodHandle targetMethod;
  private final Object targetBean;
  private final RiskLevel riskLevel;
  private final Class<?> returnType;

  /** 参数名称列表（按声明顺序），用于 map-to-args 转换 */
  private final String[] paramNames;

  private ToolMetadata(Builder builder) {
    this.name = Objects.requireNonNull(builder.name, "name must not be null");
    this.description = builder.description != null ? builder.description : "";
    this.inputSchema = Objects.requireNonNull(builder.inputSchema, "inputSchema must not be null");
    this.targetMethod =
        Objects.requireNonNull(builder.targetMethod, "targetMethod must not be null");
    this.targetBean = builder.targetBean;
    this.riskLevel = builder.riskLevel != null ? builder.riskLevel : RiskLevel.LOW;
    this.returnType = builder.returnType;
    this.paramNames = builder.paramNames != null ? builder.paramNames : new String[0];
  }

  /**
   * 使用提供的参数 map 执行此工具方法。
   *
   * <p>参数匹配逻辑：
   *
   * <ul>
   *   <li>无参数方法 → 直接调用
   *   <li>单参数且为 Map 类型 → 将整个 params map 作为唯一参数
   *   <li>多参数 → 按 {@link #paramNames} 顺序从 map 中提取值
   * </ul>
   *
   * @param params 参数键值对（key 与参数名或 {@link ToolParam#value()} 对应）
   * @return 方法返回值
   * @throws Throwable 方法执行过程中抛出的任何异常
   */
  public Object invoke(Map<String, Object> params) throws Throwable {
    int paramCount = targetMethod.type().parameterCount();
    Class<?>[] paramTypes = targetMethod.type().parameterArray();

    // 无参数方法（MethodHandle 可能包含或不包含 receiver）
    if (paramCount == 0) {
      if (targetBean != null) {
        return targetMethod.invoke(targetBean);
      }
      return targetMethod.invoke();
    }

    // 只有 receiver 没有其他参数的情况（如 noop() 方法被 unreflect 后 type 为 (Bean)String）
    if (paramCount == 1
        && targetBean != null
        && paramTypes[0].isAssignableFrom(targetBean.getClass())) {
      return targetMethod.invoke(targetBean);
    }

    // 处理 null/empty params
    if (params == null) {
      params = Map.of();
    }

    // 单参数且参数类型为 Map — 将整个 params 作为参数传入
    if (paramCount == 1 && targetMethod.type().parameterType(0).isAssignableFrom(Map.class)) {
      if (targetBean != null) {
        return targetMethod.invoke(targetBean, params);
      }
      return targetMethod.invoke(params);
    }

    // 按参数名从 map 中提取值
    boolean hasReceiver = false;
    if (targetBean != null
        && paramCount > 0
        && paramTypes[0].isAssignableFrom(targetBean.getClass())) {
      hasReceiver = true;
    }

    int startIdx = 0;
    if (hasReceiver) {
      startIdx = 1; // 跳过 receiver 参数，它是第一个
    }

    int argLen = paramCount - (hasReceiver ? 1 : 0);
    Object[] args = new Object[paramCount];
    int paramIdx = startIdx; // 从 params 中取的参数索引

    for (int i = 0; i < paramCount; i++) {
      if (hasReceiver && i == 0) {
        // 第一个参数是 receiver，用 targetBean 填充
        args[i] = targetBean;
        continue;
      }

      String paramName =
          (paramIdx - startIdx < paramNames.length)
              ? paramNames[paramIdx - startIdx]
              : "arg" + (paramIdx - startIdx);
      Object value = params.get(paramName);

      if (value == null && !params.containsKey(paramName)) {
        // 参数在 map 中缺失 — 如果是基本类型，给默认值
        Class<?> type = paramTypes[i];
        if (type.isPrimitive()) {
          if (type == int.class) args[i] = 0;
          else if (type == long.class) args[i] = 0L;
          else if (type == double.class) args[i] = 0.0d;
          else if (type == float.class) args[i] = 0.0f;
          else if (type == boolean.class) args[i] = false;
          else if (type == char.class) args[i] = '\u0000';
          else if (type == short.class) args[i] = (short) 0;
          else if (type == byte.class) args[i] = (byte) 0;
          else args[i] = null;
        } else {
          args[i] = null;
        }
      } else {
        // 类型转换（支持 Number 子类型间的转换）
        args[i] = convertValue(value, paramTypes[i]);
      }
      paramIdx++;
    }

    return targetMethod.invokeWithArguments(args);
  }

  /** 将值转换为目标类型（支持自动拆装箱和数字类型转换）。 */
  private static Object convertValue(Object value, Class<?> targetType) {
    if (value == null) return null;
    if (targetType.isInstance(value)) return value;

    // 数字类型转换
    if (value instanceof Number num) {
      if (targetType == int.class || targetType == Integer.class) return num.intValue();
      if (targetType == long.class || targetType == Long.class) return num.longValue();
      if (targetType == double.class || targetType == Double.class) return num.doubleValue();
      if (targetType == float.class || targetType == Float.class) return num.floatValue();
      if (targetType == short.class || targetType == Short.class) return num.shortValue();
      if (targetType == byte.class || targetType == Byte.class) return num.byteValue();
    }

    // String 到其他类型的转换
    if (value instanceof String s) {
      if (targetType == int.class || targetType == Integer.class) return Integer.parseInt(s);
      if (targetType == long.class || targetType == Long.class) return Long.parseLong(s);
      if (targetType == double.class || targetType == Double.class) return Double.parseDouble(s);
      if (targetType == boolean.class || targetType == Boolean.class)
        return Boolean.parseBoolean(s);
    }

    return value;
  }

  // ========== Getters ==========

  public String name() {
    return name;
  }

  public String description() {
    return description;
  }

  public JsonNode inputSchema() {
    return inputSchema;
  }

  public MethodHandle targetMethod() {
    return targetMethod;
  }

  public Object targetBean() {
    return targetBean;
  }

  public RiskLevel riskLevel() {
    return riskLevel;
  }

  public Class<?> returnType() {
    return returnType;
  }

  public String[] paramNames() {
    return paramNames;
  }

  @Override
  public String toString() {
    return "ToolMetadata{name='" + name + "', risk=" + riskLevel + "}";
  }

  // ========== Builder ==========

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private String name;
    private String description;
    private JsonNode inputSchema;
    private MethodHandle targetMethod;
    private Object targetBean;
    private RiskLevel riskLevel;
    private Class<?> returnType;
    private String[] paramNames;

    private Builder() {}

    public Builder name(String name) {
      this.name = name;
      return this;
    }

    public Builder description(String description) {
      this.description = description;
      return this;
    }

    public Builder inputSchema(JsonNode inputSchema) {
      this.inputSchema = inputSchema;
      return this;
    }

    public Builder targetMethod(MethodHandle targetMethod) {
      this.targetMethod = targetMethod;
      return this;
    }

    public Builder targetBean(Object targetBean) {
      this.targetBean = targetBean;
      return this;
    }

    public Builder riskLevel(RiskLevel riskLevel) {
      this.riskLevel = riskLevel;
      return this;
    }

    public Builder returnType(Class<?> returnType) {
      this.returnType = returnType;
      return this;
    }

    public Builder paramNames(String[] paramNames) {
      this.paramNames = paramNames;
      return this;
    }

    public ToolMetadata build() {
      return new ToolMetadata(this);
    }
  }
}
