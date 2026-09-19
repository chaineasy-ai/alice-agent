/*
 * Alice Agent — RPC 门面（bootstrap SPI 实现）
 *
 * 两种形态：
 *   --stdio / --mode rpc     → stdio JSONL（被外部调度器驱动，对应 pi --mode rpc）
 *   --mode http（默认）       → HTTP RPC 2.0（/api/v1/... + SSE）
 * 组合根用 alice-agent-runtime 的 AgentComposer（本模块不 import core ✓）。
 */
package org.cland.alice.facade.rpc;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import org.cland.alice.agent.spi.AliceFacade;
import org.cland.alice.runtime.AgentHost;
import org.cland.alice.runtime.compose.AgentComposer;
import org.cland.alice.runtime.compose.StartupBanner;
import org.cland.alice.runtime.transport.StdioJsonlTransport;

/**
 * RPC 门面 —— 常驻协议服务（HTTP 或 stdio）。
 *
 * <p>参数：
 *
 * <ul>
 *   <li>{@code --stdio} 或 {@code --mode rpc}：stdio JSONL 模式（stdout 只写协议帧，日志走 stderr）
 *   <li>{@code --port <n>}：HTTP 端口（默认 8080；0 = 随机）
 *   <li>{@code --session <id>}：会话 ID（缺省自动生成）
 *   <li>{@code --model <id>}：模型 ID（缺省探测默认）
 *   <li>{@code --no-wal}：不挂 WAL（临时/测试用）
 * </ul>
 */
public final class AliceRpcFacade implements AliceFacade {

  /** HTTP 默认端口。 */
  public static final int DEFAULT_PORT = 8080;

  private final CountDownLatch stop = new CountDownLatch(1);
  private volatile RpcServer http;

  @Override
  public String name() {
    return "rpc";
  }

  @Override
  public int launch(String[] args) {
    return launch(args, System.in, System.out);
  }

  /**
   * 可注入 stdio 的入口（测试/嵌入用）。
   *
   * @param args 参数（见类注释）
   * @param in stdio 模式输入（协议帧）
   * @param out stdio 模式输出（协议帧）
   * @return 退出码（0 正常；70 装配失败；74 启动失败）
   */
  int launch(String[] args, InputStream in, OutputStream out) {
    String[] argv = args == null ? new String[0] : args;
    boolean stdio = has(argv, "--stdio") || "rpc".equalsIgnoreCase(value(argv, "--mode"));
    AgentComposer.Composed composed;
    try {
      composed =
          AgentComposer.compose(
              new AgentComposer.Options(
                  value(argv, "--session"),
                  value(argv, "--model"),
                  !has(argv, "--no-wal"),
                  true,
                  java.util.List.of(stdio ? "stdio" : "http")));
    } catch (RuntimeException e) {
      System.err.println("[rpc] 装配失败：" + e.getMessage());
      return 70;
    }
    // 启动横幅（stdio 模式必须走 stderr：stdout 是协议通道 ✓）
    StartupBanner.print(System.err, composed.banner());
    AgentHost host = new AgentHost(composed.engine());

    if (stdio) {
      StdioJsonlTransport transport = new StdioJsonlTransport(in, out);
      transport.start(host);
      try {
        transport.runBlocking();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      transport.close();
      return 0;
    }

    int port = intValue(argv, "--port", DEFAULT_PORT);
    RpcServer server = new RpcServer(port);
    try {
      server.start(host);
    } catch (IOException e) {
      System.err.println("[rpc] 启动失败（端口 " + port + " 被占？）：" + e.getMessage());
      return 74;
    }
    http = server;
    Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "alice-rpc-shutdown"));
    System.err.println(
        "[rpc] HTTP RPC 2.0 · http://127.0.0.1:"
            + server.port()
            + "/api/v1 · 会话 "
            + composed.sessionId()
            + "（Ctrl+C 退出）");
    try {
      stop.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    closeQuietly();
    return 0;
  }

  /** 停服（shutdown hook / 测试用；幂等）。 */
  public void shutdown() {
    stop.countDown();
    closeQuietly();
  }

  /** 当前 HTTP 服务（未启动/stdio 模式为 null；运维与测试可读端口）。 */
  public RpcServer server() {
    return http;
  }

  private void closeQuietly() {
    RpcServer s = http;
    if (s != null) {
      s.close();
    }
  }

  // ──────────────────────────────────────────────────────────────────────────
  // 参数解析（极简；不引三方 CLI 库）
  // ──────────────────────────────────────────────────────────────────────────

  private static boolean has(String[] args, String flag) {
    for (String a : args) {
      if (flag.equals(a)) {
        return true;
      }
    }
    return false;
  }

  private static String value(String[] args, String flag) {
    for (int i = 0; i + 1 < args.length; i++) {
      if (flag.equals(args[i])) {
        return args[i + 1];
      }
    }
    return null;
  }

  private static int intValue(String[] args, String flag, int fallback) {
    String v = value(args, flag);
    if (v == null || v.isBlank()) {
      return fallback;
    }
    try {
      return Integer.parseInt(v.trim());
    } catch (NumberFormatException e) {
      return fallback;
    }
  }
}
