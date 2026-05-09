package org.cland.alice.facade.cmd.render;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.cland.alice.core.agent.result.StepResult;
import org.cland.alice.facade.cmd.config.RunConfig;

/**
 * JSON 结构输出渲染器（{@code --json} 模式）。
 *
 * <p>每个中间步骤输出一个 JSON 行（JSON Lines 格式）， 最终结果输出一个包含完整信息的 JSON 对象。 适合 CI/CD 管道集成和程序化处理。
 */
public final class JsonOutputRenderer implements OutputRenderer {

  private static final ObjectMapper MAPPER =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  @Override
  public void render(StepResult stepResult, RunConfig config) {
    if (stepResult == null) {
      return;
    }

    Map<String, Object> event = new LinkedHashMap<>();
    event.put("type", "step");
    event.put("timestamp", Instant.now().toString());

    switch (stepResult) {
      case StepResult.Continue cont -> {
        var action = cont.nextAction();
        var observation = cont.observation();
        event.put("phase", "continue");

        if (action != null) {
          Map<String, Object> actionMap = new LinkedHashMap<>();
          actionMap.put("type", action.type().name());
          actionMap.put("target", action.target());
          actionMap.put("actionId", action.actionId());
          if (config.verbose() && action.thought() != null) {
            actionMap.put("thought", action.thought());
          }
          event.put("action", actionMap);
        }

        if (observation != null) {
          Map<String, Object> obsMap = new LinkedHashMap<>();
          obsMap.put("status", observation.status().name());
          obsMap.put("summary", observation.summary());
          event.put("observation", obsMap);
        }
      }
      case StepResult.Finish fin -> {
        event.put("phase", "finish");
        event.put("answer", fin.answer());
        if (fin.summary() != null) {
          event.put("summary", fin.summary());
        }
      }
      case StepResult.Failure fail -> {
        event.put("phase", "failure");
        event.put("error", fail.errorMessage());
        if (fail.cause() != null) {
          event.put("cause", fail.cause().getMessage());
        }
      }
    }

    printJson(event);
  }

  @Override
  public void renderFinal(String summary, RunConfig config) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("type", "final");
    result.put("timestamp", Instant.now().toString());
    result.put("status", "completed");
    result.put("result", summary);

    printJson(result);
  }

  @Override
  public void renderError(String errorMessage, RunConfig config) {
    Map<String, Object> error = new LinkedHashMap<>();
    error.put("type", "error");
    error.put("timestamp", Instant.now().toString());
    error.put("status", "error");
    error.put("message", errorMessage);

    printJson(error);
  }

  // ========================================================================
  // 辅助
  // ========================================================================

  private void printJson(Map<String, Object> data) {
    try {
      System.out.println(MAPPER.writeValueAsString(data));
    } catch (JsonProcessingException e) {
      System.err.println("JSON serialization error: " + e.getMessage());
      System.err.println("Fallback: " + data);
    }
  }
}
