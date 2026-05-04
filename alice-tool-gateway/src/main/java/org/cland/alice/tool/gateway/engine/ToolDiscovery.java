package org.cland.alice.tool.gateway.engine;

import com.fasterxml.jackson.databind.JsonNode;
import org.cland.alice.tool.gateway.ToolRegistry;
import org.cland.alice.tool.gateway.annotation.AgentTool;
import org.cland.alice.tool.gateway.annotation.RiskLevel;
import org.cland.alice.tool.gateway.annotation.ToolParam;
import org.cland.alice.tool.gateway.metadata.ToolMetadata;
import org.cland.alice.tool.gateway.schema.SchemaGenerator;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 工具发现器 — 扫描带 {@link AgentTool} 注解的方法并注册到 {@link ToolRegistry}。
 * <p>
 * 对应设计文档 §2 动态能力注入流程。
 * <p>
 * 使用纯反射实现，不依赖 CDI 容器。扫描逻辑：
 * <ol>
 *   <li>接收预实例化的 Bean 列表（由上层传入）</li>
 *   <li>遍历 Bean 的所有 public 方法，筛选带 {@link AgentTool} 的方法</li>
 *   <li>解析方法签名，使用 {@link SchemaGenerator} 生成 JSON Schema</li>
 *   <li>创建 {@link ToolMetadata} 并注册到 {@link ToolRegistry}</li>
 * </ol>
 */
public class ToolDiscovery {

    private final ToolRegistry registry;
    private final MethodHandles.Lookup lookup;

    /**
     * @param registry 工具注册中心
     */
    public ToolDiscovery(ToolRegistry registry) {
        this.registry = registry;
        this.lookup = MethodHandles.lookup();
    }

    /**
     * 扫描并注册给定的 Bean 实例列表中所有带 {@link AgentTool} 的方法。
     *
     * @param beans 待扫描的 Bean 实例列表
     * @return 成功注册的工具数量
     */
    public int scanAndRegister(List<Object> beans) {
        if (beans == null || beans.isEmpty()) {
            return 0;
        }

        int count = 0;
        List<Throwable> errors = new ArrayList<>();

        for (Object bean : beans) {
            count += scanBean(bean, errors);
        }

        if (!errors.isEmpty()) {
            RuntimeException combined = new RuntimeException(
                "ToolDiscovery encountered " + errors.size() + " error(s) during registration"
            );
            for (Throwable e : errors) {
                combined.addSuppressed(e);
            }
            throw combined;
        }

        return count;
    }

    /**
     * 扫描单个 Bean 实例。
     */
    private int scanBean(Object bean, List<Throwable> errors) {
        int count = 0;
        Class<?> beanClass = bean.getClass();

        for (Method method : beanClass.getMethods()) {
            AgentTool annotation = method.getAnnotation(AgentTool.class);
            if (annotation == null) {
                continue;
            }

            try {
                ToolMetadata metadata = buildMetadata(bean, method, annotation);
                registry.register(metadata);
                count++;
            } catch (Throwable e) {
                errors.add(new RuntimeException(
                    "Failed to register tool [" + annotation.name()
                        + "] from method " + method.getName()
                        + " in class " + beanClass.getName(),
                    e
                ));
            }
        }

        return count;
    }

    /**
     * 从带注解的方法构建 ToolMetadata。
     */
    private ToolMetadata buildMetadata(Object bean, Method method, AgentTool annotation)
        throws IllegalAccessException {

        String toolName = annotation.name();
        String description = annotation.description();
        RiskLevel riskLevel = annotation.risk();

        // 生成 JSON Schema
        JsonNode inputSchema = SchemaGenerator.generateSchema(method);

        // 创建 MethodHandle：使用 public lookup 处理 public 方法
        // 由于 @AgentTool 标注的方法应是 public 的，使用 publicLookup 可避免模块访问限制
        var methodHandle = lookup.unreflect(method);

        // 提取参数名称（优先使用 @ToolParam value，其次使用反射参数名）
        String[] paramNames = extractParamNames(method);

        return ToolMetadata.builder()
            .name(toolName)
            .description(description)
            .inputSchema(inputSchema)
            .targetMethod(methodHandle)
            .targetBean(bean)
            .riskLevel(riskLevel)
            .returnType(method.getReturnType())
            .paramNames(paramNames)
            .build();
    }

    /**
     * 从方法中提取参数名称列表。
     * 优先使用 {@link ToolParam#value()}，如果不存在则尝试使用反射参数名。
     */
    private static String[] extractParamNames(Method method) {
        var params = method.getParameters();
        String[] names = new String[params.length];
        for (int i = 0; i < params.length; i++) {
            ToolParam tp = params[i].getAnnotation(ToolParam.class);
            if (tp != null && !tp.value().isEmpty()) {
                names[i] = tp.value();
            } else {
                // 使用反射参数名（需要编译时 -parameters 选项支持）
                String reflectName = params[i].getName();
                names[i] = reflectName;
            }
        }
        return names;
    }
}
