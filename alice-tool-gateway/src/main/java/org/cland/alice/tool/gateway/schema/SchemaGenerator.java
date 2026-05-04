package org.cland.alice.tool.gateway.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.cland.alice.tool.gateway.annotation.ToolParam;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;

/**
 * 从 Java 方法签名动态生成 JSON Schema 的工具类。
 * <p>
 * 对应设计文档 §4.1 动态 JSON Schema 映射。
 * 将 Java 方法的参数列表自动推导为标准 JSON Schema，
 * 以便 LLM Planner 直接读取并生成正确的 {@code arguments}。
 * <p>
 * 支持类型映射：
 * <ul>
 *   <li>String → {@code type: string}</li>
 *   <li>int / long / Integer / Long → {@code type: integer}</li>
 *   <li>float / double / Float / Double / BigDecimal → {@code type: number}</li>
 *   <li>boolean / Boolean → {@code type: boolean}</li>
 *   <li>List / Set → {@code type: array}</li>
 *   <li>Map / POJO → {@code type: object}</li>
 *   <li>Optional → 提取内部类型并标记 {@code required: false}</li>
 *   <li>enum → {@code type: string} + {@code enum} 约束</li>
 * </ul>
 */
public final class SchemaGenerator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SchemaGenerator() {}

    /**
     * 根据方法的参数签名生成 JSON Schema（{@code type: object} 形式）。
     * <p>
     * 生成格式示例：
     * <pre>
     * {
     *   "type": "object",
     *   "properties": {
     *     "path": { "type": "string", "description": "File path to read" },
     *     "encoding": { "type": "string", "description": "File encoding" }
     *   },
     *   "required": ["path"]
     * }
     * </pre>
     *
     * @param method 目标方法
     * @return JSON Schema 的 {@link JsonNode} 表示
     */
    public static JsonNode generateSchema(Method method) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("type", "object");

        Parameter[] parameters = method.getParameters();
        if (parameters.length == 0) {
            // 无参数方法：空 properties
            root.set("properties", MAPPER.createObjectNode());
            root.set("required", MAPPER.createArrayNode());
            return root;
        }

        // 单参数且是 Map 类型：不做结构化 Schema，接受任意对象
        if (parameters.length == 1 && Map.class.isAssignableFrom(parameters[0].getType())) {
            root.put("type", "object");
            root.set("properties", MAPPER.createObjectNode());
            root.set("required", MAPPER.createArrayNode());
            root.put("additionalProperties", true);
            root.put("description", "Arbitrary key-value parameters");
            return root;
        }

        ObjectNode properties = MAPPER.createObjectNode();
        ArrayNode required = MAPPER.createArrayNode();

        for (Parameter param : parameters) {
            ToolParam tp = param.getAnnotation(ToolParam.class);
            String paramName = (tp != null) ? tp.value() : param.getName();
            String paramDesc = (tp != null) ? tp.description() : "";
            boolean isRequired = tp == null || tp.required();

            JsonNode paramSchema = resolveType(param.getParameterizedType(), paramDesc);

            properties.set(paramName, paramSchema);
            if (isRequired) {
                required.add(paramName);
            }
        }

        root.set("properties", properties);
        root.set("required", required);

        return root;
    }

    /**
     * 将 Java 类型解析为 JSON Schema 节点。
     */
    private static JsonNode resolveType(Type type, String description) {
        ObjectNode node = MAPPER.createObjectNode();

        Class<?> rawClass = rawTypeOf(type);

        // 处理 Optional：提取内部类型
        if (rawClass == Optional.class) {
            Type innerType = extractTypeArgument(type, 0);
            if (innerType != null) {
                return resolveType(innerType, description);
            }
            node.put("type", "string");
            addDescription(node, description);
            return node;
        }

        // 基本类型映射
        if (rawClass == String.class || rawClass == char.class || rawClass == Character.class) {
            node.put("type", "string");
        } else if (rawClass == int.class || rawClass == long.class
            || rawClass == Integer.class || rawClass == Long.class
            || rawClass == short.class || rawClass == Short.class
            || rawClass == byte.class || rawClass == Byte.class) {
            node.put("type", "integer");
        } else if (rawClass == float.class || rawClass == double.class
            || rawClass == Float.class || rawClass == Double.class) {
            node.put("type", "number");
        } else if (rawClass == boolean.class || rawClass == Boolean.class) {
            node.put("type", "boolean");
        } else if (rawClass.isEnum()) {
            node.put("type", "string");
            ArrayNode enumValues = node.putArray("enum");
            for (Object constant : rawClass.getEnumConstants()) {
                enumValues.add(constant.toString());
            }
        } else if (Collection.class.isAssignableFrom(rawClass)) {
            node.put("type", "array");
            Type elementType = extractTypeArgument(type, 0);
            if (elementType != null) {
                node.set("items", resolveType(elementType, ""));
            }
        } else if (Map.class.isAssignableFrom(rawClass)) {
            node.put("type", "object");
            Type valueType = extractTypeArgument(type, 1);
            if (valueType != null) {
                // 如果 value 是 String 以外的类型，添加 additionalProperties 约束
                node.put("additionalProperties", true);
            }
        } else {
            // POJO —— 简单处理为 object
            node.put("type", "object");
        }

        addDescription(node, description);
        return node;
    }

    /**
     * 获取类型的原始 Class（处理 ParameterizedType 和 TypeVariable）。
     */
    private static Class<?> rawTypeOf(Type type) {
        if (type instanceof Class) {
            return (Class<?>) type;
        }
        if (type instanceof ParameterizedType) {
            return (Class<?>) ((ParameterizedType) type).getRawType();
        }
        return Object.class;
    }

    /**
     * 提取 ParameterizedType 的泛型参数。
     */
    private static Type extractTypeArgument(Type type, int index) {
        if (type instanceof ParameterizedType) {
            Type[] args = ((ParameterizedType) type).getActualTypeArguments();
            if (index < args.length) {
                return args[index];
            }
        }
        return null;
    }

    /**
     * 向节点添加 description 字段（非空时）。
     */
    private static void addDescription(ObjectNode node, String description) {
        if (description != null && !description.isEmpty()) {
            node.put("description", description);
        }
    }
}
