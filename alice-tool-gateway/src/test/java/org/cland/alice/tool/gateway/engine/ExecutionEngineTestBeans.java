package org.cland.alice.tool.gateway.engine;

import java.util.Map;
import org.cland.alice.tool.gateway.annotation.AgentTool;
import org.cland.alice.tool.gateway.annotation.RiskLevel;
import org.cland.alice.tool.gateway.annotation.ToolParam;

/** ExecutionEngine 测试用的 Bean，定义在 Java 中以确保注解可用。 */
public class ExecutionEngineTestBeans {

  public static class TestTools {

    @AgentTool(name = "greet", description = "Greets someone", risk = RiskLevel.LOW)
    public String greet(@ToolParam("name") String name) {
      return "Hello, " + name + "!";
    }

    @AgentTool(name = "ping", description = "Returns pong")
    public String ping() {
      return "pong";
    }

    @AgentTool(name = "add", description = "Adds two numbers")
    public int add(@ToolParam("a") int a, @ToolParam("b") int b) {
      return a + b;
    }

    @AgentTool(name = "getPerson", description = "Gets person info")
    public Map<String, Object> getPerson(@ToolParam("id") int id) {
      return Map.of("id", id, "name", "Alice", "age", 30);
    }

    @AgentTool(name = "fail", description = "Always fails", risk = RiskLevel.HIGH)
    public String fail() {
      throw new RuntimeException("Intentional failure for testing");
    }
  }
}
