/*
 * Alice Agent — AcpClient
 *
 * ACP 协议客户端包装器 — 封装 ACP Java SDK 的 AcpSyncClient，
 * 提供初始化、会话创建、提示发送和关闭的三阶段生命周期。
 */
package org.cland.alice.agent.internal.acp;

import java.net.URI;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ACP 协议客户端包装器。
 *
 * <p>封装 ACP Java SDK 的同步客户端，提供简化的三阶段生命周期：
 *
 * <ol>
 *   <li>{@link #initialize(String)} — 连接到外部 ACP Agent
 *   <li>{@link #newSession()} — 创建新的 ACP 工作会话
 *   <li>{@link #prompt(String, String)} — 发送提示并接收响应
 * </ol>
 *
 * <p>连接失败时抛出 {@link AcpClientException}。当 ACP SDK 不可用时，所有方法返回默认失败结果， 避免类加载错误。使用反射加载 ACP SDK 类，确保
 * JPMS 模块兼容性。
 */
public class AcpClientWrapper implements AutoCloseable {

  private static final Logger logger = LoggerFactory.getLogger(AcpClientWrapper.class);

  private final URI endpoint;
  private final long timeoutMs;
  private Object client;
  private String currentSessionId;
  private boolean initialized;

  /** 默认提示超时（30 秒） */
  public static final long DEFAULT_TIMEOUT_MS = 30_000;

  /**
   * 创建 ACP 客户端包装器。
   *
   * @param endpoint ACP Agent 端点 URL
   */
  public AcpClientWrapper(URI endpoint) {
    this(endpoint, DEFAULT_TIMEOUT_MS);
  }

  /**
   * 创建 ACP 客户端包装器，指定超时。
   *
   * @param endpoint ACP Agent 端点 URL
   * @param timeoutMs 提示超时（毫秒）
   */
  public AcpClientWrapper(URI endpoint, long timeoutMs) {
    this.endpoint = Objects.requireNonNull(endpoint, "endpoint must not be null");
    this.timeoutMs = timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;
  }

  /**
   * 初始化 ACP 连接 — 握手阶段。
   *
   * <p>使用反射加载 ACP SDK 客户端，建立与 ACP Agent 的连接。
   *
   * @param workspacePath ACP Agent 工作区路径
   * @throws AcpClientException 如果连接失败或 ACP SDK 不可用
   */
  public void initialize(String workspacePath) {
    try {
      logger.info(
          "Initializing ACP connection to endpoint={}, workspace={}", endpoint, workspacePath);

      // 使用反射构建 ACP 客户端，避免 JPMS 模块限制
      this.client = buildAcpSyncClient();
      callInitialize(client, workspacePath);
      this.initialized = true;
      logger.info("ACP connection initialized successfully to {}", endpoint);

    } catch (AcpClientException e) {
      throw e;
    } catch (Exception e) {
      throw new AcpClientException("Failed to initialize ACP connection to " + endpoint, e);
    }
  }

  /**
   * 创建新的 ACP 工作会话。
   *
   * @return ACP 会话 ID
   * @throws AcpClientException 如果会话创建失败或客户端未初始化
   */
  public String newSession() {
    checkInitialized();
    try {
      this.currentSessionId = callNewSession(client);
      logger.info("ACP session created: {}", currentSessionId);
      return currentSessionId;
    } catch (Exception e) {
      throw new AcpClientException("Failed to create ACP session", e);
    }
  }

  /**
   * 向 ACP Agent 发送提示并接收响应。
   *
   * @param prompt 提示文本
   * @return ACP Agent 的响应文本
   * @throws AcpClientException 如果提示发送失败
   */
  public String prompt(String prompt) {
    return prompt(currentSessionId, prompt);
  }

  /**
   * 向指定 ACP 会话发送提示并接收响应。
   *
   * @param sessionId ACP 会话 ID
   * @param prompt 提示文本
   * @return ACP Agent 的响应文本
   * @throws AcpClientException 如果提示发送失败
   */
  public String prompt(String sessionId, String prompt) {
    checkInitialized();
    try {
      logger.debug("Sending prompt to ACP session={}, prompt={}", sessionId, truncate(prompt, 100));
      String resultText = callPrompt(client, sessionId, prompt);
      logger.debug("ACP response received: {}", truncate(resultText, 200));
      return resultText;
    } catch (Exception e) {
      throw new AcpClientException("Failed to send prompt to ACP agent", e);
    }
  }

  /**
   * 获取当前 ACP 会话 ID。
   *
   * @return 会话 ID，可能为 null
   */
  public String getSessionId() {
    return currentSessionId;
  }

  /**
   * 是否已初始化。
   *
   * @return true 如果已成功初始化
   */
  public boolean isInitialized() {
    return initialized;
  }

  /** 关闭 ACP 连接。 */
  @Override
  public void close() {
    if (client != null) {
      try {
        callClose(client);
        logger.info("ACP connection closed to {}", endpoint);
      } catch (Exception e) {
        logger.warn("Error closing ACP connection to {}", endpoint, e);
      }
    }
    initialized = false;
    currentSessionId = null;
  }

  // ========== 反射辅助（用于 JPMS 兼容） ==========

  private Object buildAcpSyncClient() throws Exception {
    // 使用 Class.forName 加载 ACP SDK 类，这样即使模块系统限制也能工作
    Class<?> acpClientFactory = Class.forName("com.agentclientprotocol.sdk.client.AcpClient");
    Class<?> transportClass =
        Class.forName("com.agentclientprotocol.sdk.client.transport.WebSocketAcpClientTransport");
    Class<?> jsonMapperClass = Class.forName("com.agentclientprotocol.sdk.util.McpJsonMapper");

    // WebSocketAcpClientTransport(URI, Object)
    Object mapper = jsonMapperClass.getMethod("getDefault").invoke(null);
    Object transport =
        transportClass.getConstructor(URI.class, Object.class).newInstance(endpoint, mapper);

    // AcpClient.sync(transport).build()
    Object syncBuilder = acpClientFactory.getMethod("sync", transportClass).invoke(null, transport);
    return syncBuilder.getClass().getMethod("build").invoke(syncBuilder);
  }

  private void callInitialize(Object client, String workspacePath) throws Exception {
    client.getClass().getMethod("initialize").invoke(client);
  }

  private String callNewSession(Object client) throws Exception {
    Class<?> newSessionRequestClass =
        Class.forName("com.agentclientprotocol.sdk.spec.AcpSchema$NewSessionRequest");
    Object request =
        newSessionRequestClass
            .getConstructor(String.class, java.util.List.class)
            .newInstance("/workspace", java.util.List.of());

    Object response =
        client.getClass().getMethod("newSession", newSessionRequestClass).invoke(client, request);
    return (String) response.getClass().getMethod("sessionId").invoke(response);
  }

  private String callPrompt(Object client, String sessionId, String prompt) throws Exception {
    Class<?> textContentClass =
        Class.forName("com.agentclientprotocol.sdk.spec.AcpSchema$TextContent");
    Class<?> promptRequestClass =
        Class.forName("com.agentclientprotocol.sdk.spec.AcpSchema$PromptRequest");

    Object content = textContentClass.getConstructor(String.class).newInstance(prompt);
    Object request =
        promptRequestClass
            .getConstructor(String.class, java.util.List.class)
            .newInstance(sessionId, java.util.List.of(content));

    Object response =
        client.getClass().getMethod("prompt", promptRequestClass).invoke(client, request);

    // 提取响应文本
    Object contentList = response.getClass().getMethod("content").invoke(response);
    if (contentList == null) return "";
    java.util.List<?> list = (java.util.List<?>) contentList;
    if (list.isEmpty()) return "";
    StringBuilder sb = new StringBuilder();
    for (Object item : list) {
      if (textContentClass.isInstance(item)) {
        sb.append((String) textContentClass.getMethod("text").invoke(item));
      }
    }
    return sb.toString();
  }

  private void callClose(Object client) throws Exception {
    client.getClass().getMethod("close").invoke(client);
  }

  // ========== 辅助 ==========

  private void checkInitialized() {
    if (!initialized || client == null) {
      throw new AcpClientException("ACP client not initialized. Call initialize() first.");
    }
  }

  private static String truncate(String s, int maxLen) {
    if (s == null) return "null";
    return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
  }
}
