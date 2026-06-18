/*
 * Alice Agent — Facade 选择器 (Pure Bootstrapper, SPI-based)
 *
 * 通过 ServiceLoader 在运行时发现 facade 实现，消除编译期对外观模块的强依赖。
 * 每个 facade 模块实现 AliceFacade SPI 接口并通过 META-INF/services 注册。
 */
package org.cland.alice.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import org.cland.alice.agent.spi.AliceFacade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 外观选择器，通过 SPI 动态发现 Facade 实现。
 *
 * <p>职责：
 *
 * <ul>
 *   <li>分析启动参数，通过 {@link ServiceLoader} 发现匹配的 Facade
 *   <li>将原始参数传递给选定的 Facade
 *   <li>未匹配时回退到默认 CLI Facade
 *   <li>统一处理启动失败的回退逻辑
 * </ul>
 *
 * <p>此模块不依赖 {@code alice-core-agent}、{@code alice-model} 或任何业务配置。
 */
public final class FacadeSelector {

  private static final Logger logger = LoggerFactory.getLogger(FacadeSelector.class);

  private FacadeSelector() {}

  /**
   * 通过 SPI 发现并启动 Facade。
   *
   * <p>参数 {@code --facade <name>} 显式指定外观名称，否则使用默认 CLI。
   *
   * @param args 原始命令行参数
   * @return 退出码
   */
  public static int launch(String[] args) {
    // 1. 加载所有 SPI Facade
    List<AliceFacade> facades = new ArrayList<>();
    ServiceLoader.load(AliceFacade.class).forEach(facades::add);

    if (facades.isEmpty()) {
      logger.error("No AliceFacade SPI implementations found on classpath");
      printUsage();
      return AliceApp.EXIT_RUNTIME_ERROR;
    }

    // 2. 解析 --facade <name> 参数
    String facadeName = extractFacadeArg(args);
    if (facadeName == null) {
      // 兼容旧参数：--tui → tui
      if (hasArg(args, "--tui") || hasArg(args, "-t")) {
        facadeName = "tui";
      } else {
        // 默认 CLI
        facadeName = "cli";
      }
    }

    logger.info("Facade selected: {}", facadeName);

    // 3. 查找匹配的 Facade
    AliceFacade facade = findFacade(facades, facadeName);
    if (facade == null) {
      logger.error(
          "No facade found for name: {} (available: {})",
          facadeName,
          facades.stream().map(AliceFacade::name).toList());
      printUsage();
      return AliceApp.EXIT_PARAM_ERROR;
    }

    // 4. 启动
    try {
      String[] filteredArgs = filterBootstrapArgs(args);
      return facade.launch(filteredArgs);
    } catch (Exception e) {
      logger.error("Facade '{}' launch failed", facadeName, e);
      return AliceApp.EXIT_RUNTIME_ERROR;
    }
  }

  // ========================================================================
  // 辅助
  // ========================================================================

  private static AliceFacade findFacade(List<AliceFacade> facades, String name) {
    for (AliceFacade f : facades) {
      if (f.name().equalsIgnoreCase(name)) {
        return f;
      }
    }
    return null;
  }

  private static String extractFacadeArg(String[] args) {
    if (args == null) return null;
    for (int i = 0; i < args.length - 1; i++) {
      if ("--facade".equals(args[i])) {
        return args[i + 1];
      }
    }
    return null;
  }

  private static boolean hasArg(String[] args, String flag) {
    if (args == null) return false;
    for (String arg : args) {
      if (arg.equals(flag)) return true;
    }
    return false;
  }

  private static String[] filterBootstrapArgs(String[] args) {
    if (args == null || args.length == 0) return args;
    List<String> filtered = new ArrayList<>();
    for (int i = 0; i < args.length; i++) {
      if ("--facade".equals(args[i])) {
        i++; // skip next arg
        continue;
      }
      if ("--tui".equals(args[i])
          || "-t".equals(args[i])
          || "--cli".equals(args[i])
          || "-c".equals(args[i])) {
        continue;
      }
      filtered.add(args[i]);
    }
    return filtered.toArray(new String[0]);
  }

  private static void printUsage() {
    System.out.println("Alice Agent v0.1.0 - AI-powered autonomous agent");
    System.out.println();
    System.out.println("Usage: alice [--facade <name>] <command> [options]");
    System.out.println();
    System.out.println("Facades (SPI):");
    System.out.println("  cli    Command-line interface (default)");
    System.out.println("  tui    Terminal UI interface");
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
    System.out.println("  --facade <name>   Select facade (cli/tui)");
    System.out.println("  --tui, -t         Shortcut for --facade tui");
    System.out.println("  --cli, -c         Shortcut for --facade cli");
    System.out.println("  --help, -h        Show this help message and exit");
    System.out.println();
    System.out.println("Examples:");
    System.out.println("  alice run \"What is the capital of France?\"");
    System.out.println("  alice --tui");
    System.out.println("  alice --facade web");
  }
}
