package org.cland.alice.tool.gateway.annotation;

/**
 * 注解测试用的 Bean，定义在 Java 中以确保 {@code @AgentTool} 和 {@code @ToolParam}
 * 注解在运行时对反射正确可见。
 */
public class AnnotationTestBeans {

    public static class AnnotatedBean {

        @AgentTool(name = "file_reader", description = "Reads content from a local file", risk = RiskLevel.HIGH)
        public String readFile(
            @ToolParam(value = "path", description = "File path to read") String path,
            @ToolParam(value = "encoding", description = "File encoding", required = false) String encoding
        ) {
            return "content";
        }

        @AgentTool(name = "file_writer", risk = RiskLevel.MEDIUM)
        public void writeFile(
            @ToolParam("path") String path,
            @ToolParam("content") String content
        ) {}

        @AgentTool(name = "compute", description = "Performs a calculation")
        public int compute(@ToolParam(value = "count", description = "Number of iterations") int count) {
            return count * 2;
        }
    }
}
