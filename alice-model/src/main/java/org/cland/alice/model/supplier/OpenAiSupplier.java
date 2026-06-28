package org.cland.alice.model.supplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
  private static final ObjectMapper MAPPER = new ObjectMapper();

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
    String requestBody = buildRequestBody(payload);

    logger.debug(
        "[OpenAiSupplier] Request body (first 500 chars): {}",
        requestBody.substring(0, Math.min(500, requestBody.length())));

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

    return parseResponse(httpResponse.body(), payload.modelId());
  }

  // ========== 请求构建（Jackson ObjectMapper） ==========

  @SuppressWarnings("unchecked")
  private String buildRequestBody(Call.Payload payload) {
    ObjectNode root = MAPPER.createObjectNode();
    root.put("model", payload.modelId());

    // 构建 messages 数组
    ArrayNode messages = root.putArray("messages");

    // 如果有 system prompt，先发 system role
    String system = payload.systemPrompt();
    if (system != null) {
      ObjectNode sysMsg = messages.addObject();
      sysMsg.put("role", "system");
      sysMsg.put("content", system);
    }

    // user role
    ObjectNode userMsg = messages.addObject();
    userMsg.put("role", "user");
    userMsg.put("content", payload.prompt());

    root.put("temperature", 0.7);

    // 附加参数（tools 等）
    Map<String, Object> params = payload.parameters();
    if (params != null && !params.isEmpty()) {
      for (var entry : params.entrySet()) {
        String key = entry.getKey();
        Object value = entry.getValue();
        if ("tools".equals(key) && value instanceof List list) {
          root.set("tools", toolsToJson(list));
        } else {
          setJsonValue(root, key, value);
        }
      }
    }

    return root.toString();
  }

  /** 将 tools 列表转为 Jackson ArrayNode。 */
  @SuppressWarnings("unchecked")
  private static ArrayNode toolsToJson(List<?> tools) {
    ArrayNode arr = MAPPER.createArrayNode();
    for (Object tool : tools) {
      if (tool instanceof Map map) {
        ObjectNode toolNode = arr.addObject();
        for (var entry : (Iterable<Map.Entry<String, Object>>) map.entrySet()) {
          setJsonValue(toolNode, entry.getKey(), entry.getValue());
        }
      }
    }
    return arr;
  }

  /** 将任意 Java 值设置到 ObjectNode 中。 */
  @SuppressWarnings("unchecked")
  private static void setJsonValue(ObjectNode node, String key, Object value) {
    if (value == null) {
      node.putNull(key);
    } else if (value instanceof String s) {
      node.put(key, s);
    } else if (value instanceof Number n) {
      node.put(key, n.doubleValue());
    } else if (value instanceof Boolean b) {
      node.put(key, b);
    } else if (value instanceof Map m) {
      ObjectNode child = node.putObject(key);
      for (var entry : (Iterable<Map.Entry<String, Object>>) m.entrySet()) {
        setJsonValue(child, entry.getKey(), entry.getValue());
      }
    } else if (value instanceof List l) {
      ArrayNode arr = node.putArray(key);
      for (Object item : l) {
        if (item instanceof Map m) {
          ObjectNode child = arr.addObject();
          for (var entry : (Iterable<Map.Entry<String, Object>>) m.entrySet()) {
            setJsonValue(child, entry.getKey(), entry.getValue());
          }
        } else if (item instanceof String s) {
          arr.add(s);
        } else if (item instanceof Number n) {
          arr.add(n.doubleValue());
        } else if (item instanceof Boolean b) {
          arr.add(b);
        } else if (item instanceof JsonNode jn) {
          arr.add(jn);
        } else {
          arr.add(item.toString());
        }
      }
    } else if (value instanceof JsonNode jn) {
      node.set(key, jn);
    } else {
      node.put(key, value.toString());
    }
  }

  // ========== 响应解析 ==========

  private Call.Response parseResponse(String responseBody, String modelId) {
    try {
      JsonNode root = MAPPER.readTree(responseBody);

      // 提取 content
      String content = null;
      JsonNode choices = root.get("choices");
      if (choices != null && choices.isArray() && choices.size() > 0) {
        JsonNode message = choices.get(0).get("message");
        if (message != null) {
          JsonNode contentNode = message.get("content");
          if (contentNode != null && !contentNode.isNull()) {
            content = contentNode.asText();
          }
        }
      }

      // 提取 token usage
      Call.TokenUsage usage = null;
      JsonNode usageNode = root.get("usage");
      if (usageNode != null) {
        int promptTokens =
            usageNode.has("prompt_tokens") ? usageNode.get("prompt_tokens").asInt() : 0;
        int completionTokens =
            usageNode.has("completion_tokens") ? usageNode.get("completion_tokens").asInt() : 0;
        if (promptTokens > 0 || completionTokens > 0) {
          usage =
              new Call.TokenUsage(promptTokens, completionTokens, promptTokens + completionTokens);
        }
      }

      // 解析 tool_calls
      List<Call.ToolCall> toolCalls = parseToolCalls(root);

      return new Call.Response(content, usage, Map.of("raw", responseBody), toolCalls);
    } catch (Exception e) {
      logger.warn("Failed to parse response, returning raw body", e);
      return Call.Response.textOnly(responseBody, null, Map.of("parseError", e.getMessage()));
    }
  }

  /** 从 Jackson JsonNode 中解析 tool_calls。 */
  private static List<Call.ToolCall> parseToolCalls(JsonNode root) {
    List<Call.ToolCall> result = new ArrayList<>();
    JsonNode choices = root.get("choices");
    if (choices == null || !choices.isArray()) return result;

    for (JsonNode choice : choices) {
      JsonNode message = choice.get("message");
      if (message == null) continue;
      JsonNode toolCallsNode = message.get("tool_calls");
      if (toolCallsNode == null || !toolCallsNode.isArray()) continue;

      for (JsonNode tc : toolCallsNode) {
        JsonNode idNode = tc.get("id");
        JsonNode function = tc.get("function");
        if (function == null) continue;
        JsonNode nameNode = function.get("name");
        JsonNode argsNode = function.get("arguments");
        String name = nameNode != null ? nameNode.asText() : null;
        String args = argsNode != null ? argsNode.asText() : "{}";
        if (name != null) {
          result.add(new Call.ToolCall(name, args));
        }
      }
    }
    return result;
  }
}
