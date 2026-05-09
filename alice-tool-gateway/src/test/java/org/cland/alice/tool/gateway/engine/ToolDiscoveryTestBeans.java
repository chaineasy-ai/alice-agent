package org.cland.alice.tool.gateway.engine;

import org.cland.alice.tool.gateway.annotation.AgentTool;
import org.cland.alice.tool.gateway.annotation.RiskLevel;
import org.cland.alice.tool.gateway.annotation.ToolParam;

/**
 * 测试用 Bean 定义在 Java 中以确保 {@code @AgentTool} 和 {@code @ToolParam} 注解在运行时对反射可见。Groovy
 * 内部静态类上的注解可能无法正确保留。
 */
public class ToolDiscoveryTestBeans {

  public static class DiscoveredBean {

    @AgentTool(name = "hello", description = "Says hello")
    public String hello(@ToolParam("name") String name) {
      return "Hello, " + name + "!";
    }

    @AgentTool(name = "square", description = "Squares a number", risk = RiskLevel.MEDIUM)
    public int square(@ToolParam("x") int x) {
      return x * x;
    }

    @AgentTool(name = "concat", description = "Concatenates strings")
    public String concat(@ToolParam("a") String a, @ToolParam("b") String b) {
      return a + b;
    }
  }

  public static class DuplicateBean {

    @AgentTool(name = "hello", description = "Duplicate")
    public String hello(@ToolParam("name") String name) {
      return "Hi, " + name + "!";
    }
  }

  public static class ExtraBean {

    @AgentTool(name = "extraTool", description = "Extra tool")
    public String extra() {
      return "extra";
    }

    @AgentTool(name = "multiply", description = "Multiplies")
    public int multiply(@ToolParam("x") int x, @ToolParam("y") int y) {
      return x * y;
    }
  }
}
