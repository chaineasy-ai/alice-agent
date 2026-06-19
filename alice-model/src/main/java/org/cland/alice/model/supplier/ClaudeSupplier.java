/*
 * Alice Agent — ClaudeSupplier
 *
 * Anthropic Claude 模型适配器，将标准 Call 请求转换为 Anthropic Messages API 格式。
 * 对应设计文档中的 ClaudeSupplier 类。
 */
package org.cland.alice.model.supplier;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import org.cland.alice.model.Call;
import org.cland.alice.model.ModelSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Anthropic Claude 模型适配器。
 *
 * <p>使用 Anthropic Messages API（v1/messages）与 Claude 模型通信。 Anthropic 的 API 与 OpenAI
 * 不兼容，需要独立的请求/响应格式。
 *
 * <p>API 参考: <a href="https://docs.anthropic.com/en/api/messages">Anthropic Messages API</a>
 */
public class ClaudeSupplier implements ModelSupplier {

  private static final Logger logger = LoggerFactory.getLogger(ClaudeSupplier.class);
  private static final String DEFAULT_BASE_URL = "https://api.anthropic.com/v1/messages";
  private static final String ANTHROPIC_VERSION = "2023-06-01";

  private final String name;
  private final String apiKey;
  private final String baseUrl;
  private final HttpClient client;

  public ClaudeSupplier(String apiKey) {
    this("anthropic", apiKey, DEFAULT_BASE_URL);
  }

  public ClaudeSupplier(String name, String apiKey, String baseUrl) {
    this.name = name;
    this.apiKey = Objects.requireNonNull(apiKey, "apiKey must not be null");
    this.baseUrl = baseUrl != null ? baseUrl : DEFAULT_BASE_URL;
    this.client =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public Call.Response request(Call call) throws Exception {
    Call.Payload payload = call.payload();

    // 1. 构建 Anthropic Messages API 请求体
    String requestBody = buildRequestBody(payload);

    // 2. 发送 HTTP 请求
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl))
            .timeout(Duration.ofSeconds(60))
            .header("Content-Type", "application/json")
            .header("x-api-key", apiKey)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build();

    logger.debug("Sending request to {} for model {}", baseUrl, payload.modelId());

    HttpResponse<String> httpResponse = client.send(request, HttpResponse.BodyHandlers.ofString());

    // 3. 检查 HTTP 状态码
    int statusCode = httpResponse.statusCode();
    if (statusCode != 200) {
      throw new RuntimeException(
          "Anthropic API returned status " + statusCode + ": " + httpResponse.body());
    }

    // 4. 解析 Anthropic 格式响应
    return parseResponse(httpResponse.body(), payload.modelId());
  }

  // ========== 请求构建 ==========

  /**
   * 构建 Anthropic Messages API 请求体。
   *
   * <p>Anthropic 格式：
   *
   * <pre>
   * {
   *   "model": "claude-3-5-sonnet-latest",
   *   "max_tokens": 1024,
   *   "messages": [{"role": "user", "content": "Hello"}]
   * }
   * </pre>
   */
  private String buildRequestBody(Call.Payload payload) {
    StringBuilder sb = new StringBuilder();
    sb.append("{");
    sb.append("\"model\":\"").append(escapeJson(payload.modelId())).append("\",");
    sb.append("\"max_tokens\":1024,");
    sb.append("\"messages\":[{\"role\":\"user\",\"content\":\"")
        .append(escapeJson(payload.prompt()))
        .append("\"}]");

    // 附加参数（如 temperature, top_p, system 等）
    Map<String, Object> params = payload.parameters();
    if (params != null && !params.isEmpty()) {
      for (var entry : params.entrySet()) {
        sb.append(",\"").append(escapeJson(entry.getKey())).append("\":");
        sb.append(formatJsonValue(entry.getValue()));
      }
    }

    sb.append("}");
    return sb.toString();
  }

  // ========== 响应解析 ==========

  /**
   * 解析 Anthropic Messages API 响应。
   *
   * <p>Anthropic 响应格式：
   *
   * <pre>
   * {
   *   "id": "msg_...",
   *   "type": "message",
   *   "role": "assistant",
   *   "content": [{"type": "text", "text": "Hello!"}],
   *   "model": "claude-3-5-sonnet-latest",
   *   "usage": {"input_tokens": 10, "output_tokens": 25}
   * }
   * </pre>
   */
  private Call.Response parseResponse(String responseBody, String modelId) {
    try {
      // Anthropic 的 content 是一个数组，找到第一个 text 类型的 content 块的 text 字段
      String content = extractAnthropicTextContent(responseBody);
      if (content == null) {
        content = responseBody; // fallback
      }

      // 提取 token usage — Anthropic 使用 input_tokens / output_tokens
      int promptTokens = 0;
      int completionTokens = 0;
      String inputStr = extractJsonField(responseBody, "input_tokens");
      String outputStr = extractJsonField(responseBody, "output_tokens");
      if (inputStr != null) promptTokens = Integer.parseInt(inputStr);
      if (outputStr != null) completionTokens = Integer.parseInt(outputStr);

      Call.TokenUsage usage =
          (inputStr != null || outputStr != null)
              ? new Call.TokenUsage(promptTokens, completionTokens, promptTokens + completionTokens)
              : null;

      // 检查是否有 stop_reason = "tool_use"（表示需要工具调用）
      String stopReason = extractJsonField(responseBody, "stop_reason");
      Map<String, Object> metadata;
      if ("tool_use".equals(stopReason)) {
        metadata = Map.of("raw", responseBody, "stop_reason", "tool_use", "supports_tools", true);
      } else {
        metadata = Map.of("raw", responseBody);
      }

      return Call.Response.textOnly(content, usage, metadata);
    } catch (Exception e) {
      logger.warn("Failed to parse Anthropic response, returning raw body", e);
      return Call.Response.textOnly(responseBody, null, Map.of("parseError", e.getMessage()));
    }
  }

  /**
   * 从 Anthropic 的 content 数组中提取第一个 text 块的文本内容。
   *
   * <p>Anthropic content 格式： [{"type": "text", "text": "Hello!"}, ...]
   */
  private static String extractAnthropicTextContent(String json) {
    if (json == null) return null;

    // 查找 "type":"text" 之后的 "text" 字段
    String typeSearch = "\"type\":\"text\"";
    int typeIdx = json.indexOf(typeSearch);
    if (typeIdx < 0) return null;

    // 在 "type":"text" 之后查找 "text":"
    String textSearch = "\"text\":\"";
    int textIdx = json.indexOf(textSearch, typeIdx);
    if (textIdx < 0) return null;

    textIdx += textSearch.length();
    StringBuilder sb = new StringBuilder();
    while (textIdx < json.length()) {
      char c = json.charAt(textIdx);
      if (c == '\\') {
        sb.append(json.charAt(textIdx + 1));
        textIdx += 2;
      } else if (c == '"') {
        break;
      } else {
        sb.append(c);
        textIdx++;
      }
    }
    return sb.toString();
  }

  // ========== 工具方法 ==========

  private static String escapeJson(String s) {
    if (s == null) return "";
    return s.replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t");
  }

  @SuppressWarnings("unchecked")
  private static String formatJsonValue(Object value) {
    if (value == null) return "null";
    if (value instanceof Number || value instanceof Boolean) return value.toString();
    if (value instanceof String s) return "\"" + escapeJson(s) + "\"";
    return "\"" + escapeJson(value.toString()) + "\"";
  }

  /** 简易 JSON 字段提取（不支持嵌套，仅用于 demo）。 */
  private static String extractJsonField(String json, String field) {
    if (json == null) return null;
    String search = "\"" + field + "\":";
    int idx = json.indexOf(search);
    if (idx < 0) return null;

    idx += search.length();
    while (idx < json.length() && json.charAt(idx) == ' ') idx++;
    if (idx >= json.length()) return null;

    char first = json.charAt(idx);
    if (first == '"') {
      StringBuilder sb = new StringBuilder();
      idx++;
      while (idx < json.length()) {
        char c = json.charAt(idx);
        if (c == '\\') {
          sb.append(json.charAt(idx + 1));
          idx += 2;
        } else if (c == '"') {
          break;
        } else {
          sb.append(c);
          idx++;
        }
      }
      return sb.toString();
    } else if (first == 'n') {
      if (idx + 3 < json.length() && json.substring(idx, idx + 4).equals("null")) {
        return null;
      }
      StringBuilder sb = new StringBuilder();
      while (idx < json.length()) {
        char c = json.charAt(idx);
        if (c == ',' || c == '}' || c == ']') break;
        sb.append(c);
        idx++;
      }
      return sb.toString().trim();
    } else {
      StringBuilder sb = new StringBuilder();
      while (idx < json.length()) {
        char c = json.charAt(idx);
        if (c == ',' || c == '}' || c == ']') break;
        sb.append(c);
        idx++;
      }
      return sb.toString().trim();
    }
  }
}
