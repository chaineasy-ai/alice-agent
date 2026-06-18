/*
 * Alice Agent — Bootstrap 模块入口点
 *
 * 对应设计文档 §2.2 中 AliceApp 实体：
 *   纯引导程序 (Pure Bootstrapper)，不感知任何业务配置或 Agent 内核。
 *   仅负责 JVM 级初始化、基础路由参数解析、以及将控制权移交给选定的 Facade。
 *
 * Facade 发现通过 SPI (ServiceLoader) 完成 — 无需编译期依赖具体 facade 模块。
 */
package org.cland.alice.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Alice Agent 系统的 JVM 入口点。
 *
 * <p>职责（对应设计文档 §3 业务流程图）：
 *
 * <ol>
 *   <li>初始化 JVM 级基础设施（日志、ShutdownHook）
 *   <li>调用 {@link FacadeSelector} 通过 SPI 发现并启动 Facade
 *   <li>将退出码传播给 JVM
 * </ol>
 *
 * <p>退出码约定：
 *
 * <ul>
 *   <li>{@code 0} — 正常退出
 *   <li>{@code 1} — 运行时错误
 *   <li>{@code 2} — 参数/配置错误
 *   <li>{@code 130} — 用户手动中断（Ctrl+C）
 * </ul>
 */
public final class AliceApp {

  private static final Logger logger = LoggerFactory.getLogger(AliceApp.class);

  public static final int EXIT_SUCCESS = 0;
  public static final int EXIT_RUNTIME_ERROR = 1;
  public static final int EXIT_PARAM_ERROR = 2;
  public static final int EXIT_INTERRUPTED = 130;

  private AliceApp() {
    // 工具类，不可实例化
  }

  /**
   * 系统主入口。
   *
   * @param args 命令行参数
   */
  public static void main(String[] args) {
    // 1. 注册 JVM 关闭钩子
    Runtime.getRuntime().addShutdownHook(new Thread(() -> logger.info("JVM shutting down...")));

    // 2. 通过 SPI 发现并启动 Facade
    int exitCode = FacadeSelector.launch(args);

    // 3. 退出
    logger.info("Alice Agent exiting with code {}", exitCode);
    System.exit(exitCode);
  }
}
