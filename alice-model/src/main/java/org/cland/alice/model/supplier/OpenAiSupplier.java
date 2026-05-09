package org.cland.alice.model.supplier;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.cland.alice.model.Call;
import org.cland.alice.model.ModelSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** OpenAI 模型适配器，将标准 Call 请求转换为 OpenAI Chat Completion API 格式。 对应设计文档中的 OpenAISupplier。 */
public class OpenAiSupplier implements ModelSupplier {

  private static final Logger logger = LoggerFactory.getLogger(OpenAiSupplier.class);
  private static final String DEFAULT_BASE_URL = "https://api.openai.com/v1/chat/completions";

  private final String name;
  private final String apiKey;
  private final String baseUrl;
  private final HttpClient client;

  public OpenAiSupplier(String apiKey) {
    this("openai", apiKey, DEFAULT_BASE_URL);
  }

  public OpenAiSupplier(String name, String apiKey, String baseUrl) {
    this.name = name;
    this.apiKey = apiKey;
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

    // 1. 构建 OpenAI 请求体
    String requestBody = buildRequestBody(payload);

    // 2. 发送 HTTP 请求
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl))
            .timeout(Duration.ofSeconds(60))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build();

    logger.debug("Sending request to {} for model {}", baseUrl, payload.modelId());

    HttpResponse<String> httpResponse = client.send(request, HttpResponse.BodyHandlers.ofString());

    // 3. 解析响应
    return parseResponse(httpResponse.body(), payload.modelId());
  }

  // ========== 请求构建 ==========

  private String buildRequestBody(Call.Payload payload) {
    // 简易 JSON 构建（生产环境建议用 JSON 库）
    StringBuilder sb = new StringBuilder();
    sb.append("{");
    sb.append("\"model\":\"").append(escapeJson(payload.modelId())).append("\",");
    sb.append("\"messages\":[{\"role\":\"user\",\"content\":\"")
        .append(escapeJson(payload.prompt()))
        .append("\"}],");
    sb.append("\"temperature\":0.7");
    // 附加参数
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

  private Call.Response parseResponse(String responseBody, String modelId) {
    // 简易 JSON 解析（生产环境建议用 JSON 库）
    try {
      // 提取 content
      String content = extractJsonField(responseBody, "content");
      if (content == null) {
        content = responseBody; // fallback
      }

      // 提取 token usage（如果有）
      int promptTokens = 0;
      int completionTokens = 0;
      String promptStr = extractJsonField(responseBody, "prompt_tokens");
      String completionStr = extractJsonField(responseBody, "completion_tokens");
      if (promptStr != null) promptTokens = Integer.parseInt(promptStr);
      if (completionStr != null) completionTokens = Integer.parseInt(completionStr);

      Call.TokenUsage usage =
          (promptStr != null || completionStr != null)
              ? new Call.TokenUsage(promptTokens, completionTokens, promptTokens + completionTokens)
              : null;

      return new Call.Response(content, usage, Map.of("raw", responseBody));
    } catch (Exception e) {
      logger.warn("Failed to parse response, returning raw body");
      return new Call.Response(responseBody, null, Map.of("parseError", e.getMessage()));
    }
  }

  // ========== 工具方法 ==========

  private static String escapeJson(String s) {
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

  /** 简易 JSON 字段提取（不支持嵌套，仅用于 demo）。 查找 "key": "value" 或 "key": 123 模式。 */
  private static String extractJsonField(String json, String field) {
    // 查找 "field":
    String search = "\"" + field + "\":";
    int idx = json.indexOf(search);
    if (idx < 0) return null;

    idx += search.length();
    // 跳过空白
    while (idx < json.length() && json.charAt(idx) == ' ') idx++;
    if (idx >= json.length()) return null;

    char first = json.charAt(idx);
    if (first == '"') {
      // 字符串值
      StringBuilder sb = new StringBuilder();
      idx++; // skip opening quote
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
    } else {
      // 数值或布尔值
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
