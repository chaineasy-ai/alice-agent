/*
 * Alice Agent — Facade 选择器
 *
 * 对应设计文档 §2.2 中 FacadeSelector 实体：
 *   决策逻辑类，分析命令行参数（如 --tui 或 -c），
 *   决定用户进入哪种交互环境，并实例化对应的 Launcher。
 */
package org.cland.alice.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.cland.alice.core.agent.Agent;

/**
 * 外观选择器，根据运行配置决定启动 CLI 还是 TUI 模式。
 * <p>
 * 对应设计文档类图中 FacadeSelector 的职责：
 * <ul>
 *   <li>分析启动参数，确定 Facade 类型</li>
 *   <li>实例化并返回对应的 Launcher</li>
 *   <li>统一处理启动失败的回退逻辑</li>
 * </ul>
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
     * <p>
     * 规则：
     * <ul>
     *   <li>如果参数包含 {@code --tui} 或 {@code -t}，返回 TUI 模式</li>
     *   <li>如果参数包含 {@code --cli} 或 {@code -c}，返回 CLI 模式</li>
     *   <li>其他情况，默认返回 CLI 模式</li>
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
     * <p>
     * 这是一个阻塞调用，直到 Launcher 执行完毕才会返回。
     *
     * @param type  Facade 类型
     * @param agent 已初始化的 Agent 核心实例
     * @param args  原始命令行参数（传递给 CLI 解析）
     * @return 退出码（0 成功，非 0 失败）
     */
    public static int launch(FacadeType type, Agent agent, String[] args) {
                logger.info("Launching {} facade...", type);

        return switch (type) {
            case CLI -> launchCli(agent, args);
            case TUI -> launchTui(agent);
        };
    }

    /**
     * 启动 CLI 模式。
     * <p>
     * 委托给 {@code org.cland.alice.facade.cmd.AliceCliLauncher}。
     * 如果未提供子命令，打印友好提示并返回成功码。
     */
    private static int launchCli(Agent agent, String[] args) {
        try {
            String[] filteredArgs = filterAppArgs(args);

            // 没有提供子命令时打印提示，不作为错误
            if (filteredArgs == null || filteredArgs.length == 0) {
                System.out.println("Alice Agent v" + AliceAgent.VERSION
                    + " — AI-powered autonomous agent");
                System.out.println();
                System.out.println("Usage: alice [--tui] <command> [options]");
                System.out.println();
                System.out.println("Commands:");
                System.out.println("  run     Execute a single task and exit");
                System.out.println("  chat    Start an interactive conversation (not yet implemented)");
                System.out.println("  tools   List all loaded tools (not yet implemented)");
                System.out.println("  config  Manage configuration (not yet implemented)");
                System.out.println();
                System.out.println("Options:");
                System.out.println("  --tui, -t     Start in TUI (terminal UI) mode");
                System.out.println("  --cli, -c     Force CLI mode (default)");
                System.out.println("  --model, -m   Override default model");
                System.out.println("  --verbose, -v Enable verbose output");
                System.out.println();
                System.out.println("Examples:");
                System.out.println("  alice run \"What is the capital of France?\"");
                System.out.println("  alice --tui");
                System.out.println("  alice run \"Write a poem\" --model gpt-4o --verbose");
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
     * <p>
     * 委托给 {@code org.cland.alice.facade.tui.AliceTuiLauncher}。
     */
    private static int launchTui(Agent agent) {
        try (var launcher = new org.cland.alice.facade.tui.AliceTuiLauncher(agent)) {
            launcher.start();
            launcher.run();
            return AliceApp.EXIT_SUCCESS;
        } catch (Exception e) {
        logger.error("TUI launch failed", e);
            return AliceApp.EXIT_RUNTIME_ERROR;
        }
    }

    // ========================================================================
    // 辅助
    // ========================================================================

    /**
     * 过滤掉 app 级别的参数，只保留传递给 Facade 的参数。
     */
    private static String[] filterAppArgs(String[] args) {
        if (args == null || args.length == 0) {
            return args;
        }

        java.util.List<String> filtered = new java.util.ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--tui":
                case "-t":
                case "--cli":
                case "-c":
                    break;
                case "--model":
                case "-m":
                case "--max-iterations":
                case "--timeout":
                    i++;
                    break;
                case "--verbose":
                case "-v":
                case "--debug":
                case "--no-pre-verify":
                case "--no-post-verify":
                    break;
                default:
                    filtered.add(args[i]);
                    break;
            }
        }
        return filtered.toArray(new String[0]);
    }
}
