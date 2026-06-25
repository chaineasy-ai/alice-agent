package org.cland.alice.model.supplier;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.cland.alice.model.Call;
import org.cland.alice.model.ModelSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OpenAI 模型适配器，将标准 Call 请求转换为 OpenAI Chat Completion API 格式。 对应设计文档中的 OpenAISupplier。
 *
 * <p>支持 Function Calling (tools)：当 {@code payload.parameters()} 中包含 {@code "tools"} 键时， 自动将其作为
 * {@code tools} 参数传给 API，并在响应中解析 {@code tool_calls}。
 */
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
    this.baseUrl = normalizeChatUrl(baseUrl != null ? baseUrl : DEFAULT_BASE_URL);
    this.client =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
  }

  /** Normalize the base URL: append /chat/completions if the path doesn't end with it. */
  private static String normalizeChatUrl(String url) {
    if (url == null) return DEFAULT_BASE_URL;
    String trimmed = url.trim();
    if (trimmed.endsWith("/chat/completions")) return trimmed;
    if (trimmed.endsWith("/")) return trimmed + "chat/completions";
    return trimmed + "/chat/completions";
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

  @SuppressWarnings("unchecked")
  private String buildRequestBody(Call.Payload payload) {
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
        String key = entry.getKey();
        Object value = entry.getValue();
        // 'tools' 参数需要特殊处理：它是 List<Map>，直接作为 JSON array
        if ("tools".equals(key) && value instanceof List) {
          sb.append(",\"tools\":");
          sb.append(formatToolsArray((List<Map<String, Object>>) value));
        } else {
          sb.append(",\"").append(escapeJson(key)).append("\":");
          sb.append(formatJsonValue(value));
        }
      }
    }
    sb.append("}");
    return sb.toString();
  }

  /** 将 tools schema 列表格式化为 JSON array。 */
  private static String formatToolsArray(List<Map<String, Object>> tools) {
    StringBuilder sb = new StringBuilder();
    sb.append("[");
    for (int i = 0; i < tools.size(); i++) {
      if (i > 0) sb.append(",");
      Map<String, Object> tool = tools.get(i);
      sb.append(formatToolObject(tool));
    }
    sb.append("]");
    return sb.toString();
  }

  /** 将单个 tool Map 格式化为 JSON object（递归处理嵌套 map/list）。 */
  @SuppressWarnings("unchecked")
  private static String formatToolObject(Map<String, Object> tool) {
    StringBuilder sb = new StringBuilder();
    sb.append("{");
    boolean first = true;
    for (var entry : tool.entrySet()) {
      if (!first) sb.append(",");
      first = false;
      sb.append("\"").append(escapeJson(entry.getKey())).append("\":");
      sb.append(formatJsonValue(entry.getValue()));
    }
    sb.append("}");
    return sb.toString();
  }

  // ========== 响应解析 ==========

  private Call.Response parseResponse(String responseBody, String modelId) {
    try {
      // 提取 content（可能为 null，当模型返回 tool_calls 时）
      String content = extractJsonField(responseBody, "content");

      // 提取 token usage
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

      // 解析 tool_calls（如果存在）
      List<Call.ToolCall> toolCalls = parseToolCalls(responseBody);

      return new Call.Response(content, usage, Map.of("raw", responseBody), toolCalls);
    } catch (Exception e) {
      logger.warn("Failed to parse response, returning raw body");
      return Call.Response.textOnly(responseBody, null, Map.of("parseError", e.getMessage()));
    }
  }

  /**
   * 从 OpenAI 响应中解析 tool_calls 数组。
   *
   * <p>格式：{"choices":[{"message":{"tool_calls":[{"id":"call_xxx","function":{"name":"xxx","arguments":"{}"}}]}}]}
   */
  private static List<Call.ToolCall> parseToolCalls(String json) {
    List<Call.ToolCall> result = new ArrayList<>();

    // 查找 "tool_calls":[ 段落后解析每个 tool call
    String search = "\"tool_calls\":[";
    int idx = json.indexOf(search);
    if (idx < 0) return result;

    idx += search.length();
    // 跳过空白
    while (idx < json.length() && json.charAt(idx) == ' ') idx++;
    if (idx >= json.length() || json.charAt(idx) != '[') {
      // 已经跳过了 [，所以不需要额外检查
    }

    // 解析数组中的元素，直到 ] 或超出
    while (idx < json.length()) {
      // 跳过空白和逗号
      while (idx < json.length()
          && (json.charAt(idx) == ' '
              || json.charAt(idx) == ','
              || json.charAt(idx) == '\n'
              || json.charAt(idx) == '\r')) idx++;
      if (idx >= json.length() || json.charAt(idx) == ']') break;
      if (json.charAt(idx) != '{') {
        idx++;
        continue;
      }

      // 找到 name 和 arguments
      String name = null;
      String arguments = null;

      // 查找 "function":{
      int funcIdx = json.indexOf("\"function\":{", idx);
      if (funcIdx < 0 || funcIdx > idx + 500) {
        idx++;
        continue;
      }

      // 从 function 块中提取 name
      int nameIdx = json.indexOf("\"name\":\"", funcIdx);
      if (nameIdx >= 0) {
        nameIdx += 8;
        StringBuilder nameSb = new StringBuilder();
        while (nameIdx < json.length()) {
          char c = json.charAt(nameIdx);
          if (c == '"') break;
          nameSb.append(c);
          nameIdx++;
        }
        name = nameSb.toString();
      }

      // 从 function 块中提取 arguments
      int argsIdx = json.indexOf("\"arguments\":\"", funcIdx);
      if (argsIdx >= 0) {
        argsIdx += 13;
        StringBuilder argsSb = new StringBuilder();
        while (argsIdx < json.length()) {
          char c = json.charAt(argsIdx);
          if (c == '\\') {
            if (argsIdx + 1 < json.length()) {
              char next = json.charAt(argsIdx + 1);
              switch (next) {
                case 'n' -> argsSb.append('\n');
                case 'r' -> argsSb.append('\r');
                case 't' -> argsSb.append('\t');
                case '"' -> argsSb.append('"');
                case '\\' -> argsSb.append('\\');
                default -> {
                  argsSb.append('\\');
                  argsSb.append(next);
                }
              }
              argsIdx += 2;
            } else {
              argsIdx++;
            }
          } else if (c == '"') {
            break;
          } else {
            argsSb.append(c);
            argsIdx++;
          }
        }
        arguments = argsSb.toString();
      }

      if (name != null) {
        result.add(new Call.ToolCall(name, arguments));
      }

      // 前进到下一个 }
      int closeIdx = json.indexOf('}', funcIdx);
      if (closeIdx < 0) break;
      // 再前进一个 } 来跳过 tool call 对象
      int objCloseIdx = json.indexOf('}', closeIdx + 1);
      if (objCloseIdx < 0) break;
      idx = objCloseIdx + 1;
    }

    return result;
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
    if (value instanceof Map m) return formatToolObject(m);
    if (value instanceof List l) {
      StringBuilder sb = new StringBuilder("[");
      for (int i = 0; i < l.size(); i++) {
        if (i > 0) sb.append(",");
        sb.append(formatJsonValue(l.get(i)));
      }
      sb.append("]");
      return sb.toString();
    }
    if (value instanceof com.fasterxml.jackson.databind.JsonNode jn) {
      return jn.toString(); // 已经是 JSON 格式，不需要再转义
    }
    return "\"" + escapeJson(value.toString()) + "\"";
  }

  /** 简易 JSON 字段提取（不支持嵌套，仅用于 demo）。 查找 "key": "value" 或 "key": 123 模式。 */
  private static String extractJsonField(String json, String field) {
    if (json == null) return null;
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
    } else if (first == 'n') {
      // null 值
      if (idx + 3 < json.length() && json.substring(idx, idx + 4).equals("null")) {
        return null;
      }
      // 数值或布尔值
      StringBuilder sb = new StringBuilder();
      while (idx < json.length()) {
        char c = json.charAt(idx);
        if (c == ',' || c == '}' || c == ']') break;
        sb.append(c);
        idx++;
      }
      return sb.toString().trim();
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
