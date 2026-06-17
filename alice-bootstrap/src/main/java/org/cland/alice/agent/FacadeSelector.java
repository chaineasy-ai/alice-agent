/*
 * Alice Agent — Facade 选择器 (Pure Bootstrapper)
 *
 * 对应设计文档 §2.2 中 FacadeSelector 实体：
 *   纯路由工具，仅通过过滤原始参数（--tui / --cli）来决定外壳路由。
 *   不再持有 Agent / AgentConfig 等业务对象，仅传递原始 String[] args。
 */
package org.cland.alice.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 外观选择器，根据纯路由参数决定启动 CLI 还是 TUI 模式。
 *
 * <p>对应设计文档类图中 FacadeSelector 的职责：
 *
 * <ul>
 *   <li>分析启动参数，确定 Facade 类型
 *   <li>将原始参数传递给对应的 Launcher
 *   <li>统一处理启动失败的回退逻辑
 * </ul>
 *
 * <p>此模块不依赖 {@code alice-core-agent}、{@code alice-model} 或任何业务配置。 业务配置的加载与内核装配由 Facade 模块在内部完成。
 */
public final class FacadeSelector {

  private static final Logger logger = LoggerFactory.getLogger(FacadeSelector.class);

  /** 支持的 Facade 类型 */
  public enum FacadeType {
    /** 命令行交互模式（默认） */
    CLI,
    /** 终端 TUI 模式 */
    TUI
  }

  private FacadeSelector() {
    // 工具类，不可实例化
  }

  /**
   * 检测启动模式。
   *
   * <p>规则：
   *
   * <ul>
   *   <li>如果参数包含 {@code --tui} 或 {@code -t}，返回 TUI 模式
   *   <li>如果参数包含 {@code --cli} 或 {@code -c}，返回 CLI 模式
   *   <li>其他情况，默认返回 CLI 模式
   * </ul>
   *
   * @param args 原始命令行参数
   * @return 检测到的 Facade 类型
   */
  public static FacadeType detect(String[] args) {
    if (args == null || args.length == 0) {
      return FacadeType.CLI;
    }

    boolean hasTuiFlag = false;
    boolean hasCliFlag = false;

    for (String arg : args) {
      switch (arg) {
        case "--tui":
        case "-t":
          hasTuiFlag = true;
          break;
        case "--cli":
        case "-c":
          hasCliFlag = true;
          break;
        default:
          break;
      }
    }

    if (hasTuiFlag && !hasCliFlag) {
      return FacadeType.TUI;
    }

    return FacadeType.CLI;
  }

  /**
   * 根据指定类型启动并运行对应的 Launcher。
   *
   * <p>这是一个阻塞调用，直到 Launcher 执行完毕才会返回。
   *
   * <p>业务配置解析、ModelProvider 初始化、Agent 内核装配等交由 Facade 模块内部完成。
   *
   * @param type Facade 类型
   * @param args 原始命令行参数（原封不动传递给 Facade）
   * @return 退出码（0 成功，非 0 失败）
   */
  public static int launch(FacadeType type, String[] args) {
    logger.info("Launching {} facade...", type);

    return switch (type) {
      case CLI -> launchCli(args);
      case TUI -> launchTui(args);
    };
  }

  /**
   * 启动 CLI 模式。
   *
   * <p>委托给 {@code org.cland.alice.facade.cmd.AliceCliLauncher}。
   */
  private static int launchCli(String[] args) {
    try {
      String[] filteredArgs = filterAppArgs(args);

      if (filteredArgs == null || filteredArgs.length == 0) {
        // 没有提供子命令时打印友好提示
        printUsage();
        return AliceApp.EXIT_SUCCESS;
      }

      return org.cland.alice.facade.cmd.AliceCliLauncher.run(filteredArgs);
    } catch (Exception e) {
      logger.error("CLI launch failed", e);
      return AliceApp.EXIT_RUNTIME_ERROR;
    }
  }

  /**
   * 启动 TUI 模式。
   *
   * <p>委托给 {@code org.cland.alice.facade.tui.AliceTuiLauncher}。 由 TUI Launcher 内部自行初始化
   * ModelProvider 和 Agent 核心。
   */
  private static int launchTui(String[] args) {
    try {
      return org.cland.alice.facade.tui.AliceTuiLauncher.launch(args);
    } catch (Exception e) {
      logger.error("TUI launch failed", e);
      return AliceApp.EXIT_RUNTIME_ERROR;
    }
  }

  // ========================================================================
  // 辅助
  // ========================================================================

  /** 打印 bootstrap 级别的使用提示。 */
  private static void printUsage() {
    System.out.println("Alice Agent v0.1.0 - AI-powered autonomous agent");
    System.out.println();
    System.out.println("Usage: alice [--tui] <command> [options]");
    System.out.println();
    System.out.println("Commands:");
    System.out.println("  run     Execute a single task and exit");
    System.out.println("  chat    Start an interactive conversation");
    System.out.println("  tools   List all loaded tools");
    System.out.println("  config  Manage configuration");
    System.out.println("  routine Register or manage scheduled routine tasks");
    System.out.println("  sub-agent Manage sub-agents");
    System.out.println();
    System.out.println("Options:");
    System.out.println("  --tui, -t     Start in TUI (terminal UI) mode");
    System.out.println("  --cli, -c     Force CLI mode (default)");
    System.out.println("  --help, -h    Show this help message and exit");
    System.out.println();
    System.out.println("Examples:");
    System.out.println("  alice run \"What is the capital of France?\"");
    System.out.println("  alice --tui");
    System.out.println("  alice run \"Write a poem\" --model gpt-4o --verbose");
  }

  /** 过滤掉 bootstrap 级别的参数，只保留传递给 Facade 的参数。 */
  private static String[] filterAppArgs(String[] args) {
    if (args == null || args.length == 0) {
      return args;
    }

    java.util.List<String> filtered = new java.util.ArrayList<>();
    for (String arg : args) {
      switch (arg) {
        case "--tui":
        case "-t":
        case "--cli":
        case "-c":
          // bootstrap 级别参数，不传递给 facade
          break;
        default:
          filtered.add(arg);
          break;
      }
    }
    return filtered.toArray(new String[0]);
  }
}
