package org.cland.alice.tool.gateway.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记一个 Java Bean 方法可以被 Agent 作为工具调用。
 * <p>
 * 配合 {@link ToolDiscovery} 在启动时扫描，自动生成 JSON Schema
 * 并注册到 {@link org.cland.alice.tool.gateway.ToolRegistry}。
 * <p>
 * 对应设计文档 §2 动态能力注入流程。
 *
 * <pre>{@code
 * @ApplicationScoped
 * public class SystemTools {
 *     @AgentTool(name = "file_reader", description = "Reads content from a local file")
 *     @RiskLevel(HIGH)
 *     public String readFile(@ToolParam("path") String path) { ... }
 * }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AgentTool {

    /** 工具名称（Agent 调用时使用的标识符） */
    String name();

    /** 工具描述（传给 LLM 的语义描述，影响 Planner 的工具选择） */
    String description() default "";

    /** 风险等级，默认 LOW */
    RiskLevel risk() default RiskLevel.LOW;
}
