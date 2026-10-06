/*
 * Alice Agent — 启动横幅（各门面统一开场输出）
 *
 * 参考 pi 的启动块格式，并补 alice 自有维度：
 *   [Context] [Skills] [Prompts] [Extensions] + [agents] [runtime] [loop]
 */
package org.cland.alice.runtime.compose;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.cland.alice.core.agent.Agent;
import org.cland.alice.core.agent.AgentConfig;
import org.cland.alice.core.agent.prompt.FilePromptLoader;
import org.cland.alice.core.agent.prompt.PromptDef;
import org.cland.alice.core.agent.prompt.RuleDef;
import org.cland.alice.tool.gateway.ToolRegistry;
import org.cland.alice.tool.gateway.ToolRegistryHolder;

/**
 * 启动横幅 —— 一次输出"这次启动到底装了什么"（便于排障与复盘）。
 *
 * <p>用法：{@code StartupBanner.print(System.err, StartupBanner.collect(agent, sessionId))} （stdio
 * 模式下必须写 stderr，stdout 留给协议帧 ✓）。
 */
public final class StartupBanner {

  /** 每块最多展示条目数（超出折叠）。 */
  private static final int MAX_ITEMS = 12;

  private StartupBanner() {}

  /**
   * 横幅数据（各块内容已渲染为单行/多行文本）。
   *
   * @param context 工程与规则上下文
   * @param skills 已注册工具/技能
   * @param prompts managed prompts
   * @param extensions 扩展（第一方/第三方）
   * @param agents 主/子 Agent
   * @param runtime 运行环境
   * @param loop 循环与内核配置
   */
  public record Data(
      String context,
      List<String> skills,
      List<String> prompts,
      List<String> extensions,
      String agents,
      String runtime,
      String loop) {

    public Data {
      skills = safe(skills);
      prompts = safe(prompts);
      extensions = safe(extensions);
    }

    private static List<String> safe(List<String> in) {
      return in == null ? List.of() : List.copyOf(in);
    }
  }

  /**
   * 采集横幅数据（绝不抛异常：任一来源不可用则降级为占位，不能因为横幅拖垮启动 ✗）。
   *
   * @param agent 已装配 Agent（不得为 null）
   * @param sessionId 会话 ID
   * @param transports 已启用的传输名（如 inprocess/stdio/http）
   * @return 横幅数据
   */
  public static Data collect(Agent agent, String sessionId, List<String> transports) {
    return collect(agent, sessionId, transports, null);
  }

  /**
   * 采集横幅数据（带 graphKernel 来源标注）。
   *
   * @param graphKernelSource graphKernel 生效来源（cli/config/default；null=不标注）
   */
  public static Data collect(
      Agent agent, String sessionId, List<String> transports, String graphKernelSource) {
    Objects.requireNonNull(agent, "agent must not be null");
    AgentConfig config = agent.config();
    String execMode = config.graphKernelEnabled() ? "graph" : "legacy";
    String kernelLine =
        "maxIterations="
            + config.maxIterations()
            + " · graphKernel="
            + config.graphKernelEnabled()
            + (graphKernelSource != null && !graphKernelSource.isBlank()
                ? " (source=" + graphKernelSource + ", exec=" + execMode + ")"
                : " (exec=" + execMode + ")")
            + " · skipMicro="
            + config.skipMicro();
    return new Data(
        contextLine(),
        skills(),
        promptNames(),
        extensions(),
        "主 Agent " + agent.agentId() + "（子 Agent 0）",
        runtimeLine(sessionId, transports),
        kernelLine);
  }

  /**
   * 渲染横幅。
   *
   * @param data 横幅数据
   * @return 多行文本（以换行结尾）
   */
  public static String render(Data data) {
    Objects.requireNonNull(data, "data must not be null");
    StringBuilder sb = new StringBuilder(512);
    append(sb, "Context", List.of(data.context()));
    append(sb, "Skills", data.skills());
    append(sb, "Prompts", data.prompts());
    append(sb, "Extensions", data.extensions());
    append(sb, "agents", List.of(data.agents()));
    append(sb, "runtime", List.of(data.runtime()));
    append(sb, "loop", List.of(data.loop()));
    return sb.toString();
  }

  /**
   * 打印横幅。
   *
   * @param out 输出流（stdio 模式传 stderr）
   * @param data 横幅数据
   */
  public static void print(PrintStream out, Data data) {
    out.print(render(data));
    out.flush();
  }

  // ──────────────────────────────────────────────────────────────────────────
  // 各块采集（全部容错）
  // ──────────────────────────────────────────────────────────────────────────

  private static String contextLine() {
    List<String> parts = new ArrayList<>();
    parts.add("AGENTS.md " + (findUpward("AGENTS.md") != null ? "✓" : "—"));
    parts.add("rules " + ruleNames().size());
    parts.add("prompts " + promptNames().size());
    return String.join(" · ", parts);
  }

  /**
   * 向上找文件（cwd → 最多 4 级父目录）。
   *
   * <p>为什么：Gradle run 的 cwd 是模块目录（如 alice-bootstrap/），工程根在上层——只查 cwd 会误报 ✗。
   *
   * @param name 文件名
   * @return 找到的路径；未找到返回 null
   */
  private static Path findUpward(String name) {
    Path dir = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath();
    for (int i = 0; i < 4 && dir != null; i++) {
      Path candidate = dir.resolve(name);
      if (Files.isReadable(candidate)) {
        return candidate;
      }
      dir = dir.getParent();
    }
    return null;
  }

  private static List<String> skills() {
    try {
      ToolRegistry registry = ToolRegistryHolder.INSTANCE.registry();
      Set<String> names = new TreeSet<>(registry.toolNames());
      return collapse(names);
    } catch (RuntimeException e) {
      return List.of("（工具注册表不可用：" + e.getMessage() + "）");
    }
  }

  private static List<String> promptNames() {
    try {
      List<PromptDef> prompts = new FilePromptLoader().getAllPrompts();
      Set<String> names = new TreeSet<>();
      for (PromptDef p : prompts) {
        names.add(p.name());
      }
      return collapse(names);
    } catch (RuntimeException e) {
      return List.of();
    }
  }

  private static List<String> ruleNames() {
    try {
      List<RuleDef> rules = new FilePromptLoader().getAllRules();
      Set<String> names = new TreeSet<>();
      for (RuleDef r : rules) {
        names.add(r.name());
      }
      return collapse(names);
    } catch (RuntimeException e) {
      return List.of();
    }
  }

  private static List<String> extensions() {
    return List.of(
        "第一方：tool-gateway · memory-vault · guardrail · env-adapter（MCP/ACP）", "第三方 SPI：—（扩展层排二期）");
  }

  private static String runtimeLine(String sessionId, List<String> transports) {
    String t = (transports == null || transports.isEmpty()) ? "—" : String.join("/", transports);
    return "session="
        + (sessionId == null ? "—" : sessionId)
        + " · JVM="
        + System.getProperty("java.version")
        + " · OS="
        + System.getProperty("os.name")
        + " · transports="
        + t;
  }

  private static List<String> collapse(Set<String> names) {
    if (names.isEmpty()) {
      return List.of("（无）");
    }
    List<String> out = new ArrayList<>(names);
    if (out.size() > MAX_ITEMS) {
      int extra = out.size() - MAX_ITEMS;
      out = new ArrayList<>(out.subList(0, MAX_ITEMS));
      out.add("…（另 " + extra + " 个）");
    }
    return out;
  }

  private static void append(StringBuilder sb, String name, List<String> lines) {
    String label = "[" + name + "]";
    String pad = " ".repeat(Math.max(1, 12 - label.length()));
    if (lines == null || lines.isEmpty()) {
      sb.append(label).append(pad).append("—").append('\n');
      return;
    }
    sb.append(label).append(pad).append(lines.get(0)).append('\n');
    for (int i = 1; i < lines.size(); i++) {
      sb.append(" ".repeat(12)).append(lines.get(i)).append('\n');
    }
  }

  /** 供测试/诊断：块的顺序与名称。 */
  public static List<String> blockNames() {
    return List.of("Context", "Skills", "Prompts", "Extensions", "agents", "runtime", "loop");
  }

  /** 供运维读原始目录（诊断用）。 */
  public static Map<String, Object> rawDirs() {
    return Map.of(
        "promptsDir", Path.of(System.getProperty("user.home"), ".alice", "prompts").toString(),
        "rulesDir", Path.of(System.getProperty("user.home"), ".alice", "rules").toString());
  }
}
