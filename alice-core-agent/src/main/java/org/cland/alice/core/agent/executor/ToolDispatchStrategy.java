package org.cland.alice.core.agent.executor;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import java.util.Objects;
import java.util.Set;
import org.cland.alice.core.agent.AgentContext;
import org.cland.alice.core.agent.AgentFacade;
import org.cland.alice.core.agent.guardrail.GuardrailToolProxy;
import org.cland.alice.core.agent.lifecycle.Action;
import org.cland.alice.core.agent.lifecycle.Observation;
import org.cland.alice.core.agent.lifecycle.StepWithContext;
import org.cland.alice.core.agent.prompt.PromptManager;
import org.cland.alice.core.agent.result.StepResult;
import org.cland.alice.core.agent.wal.WalSession;
import org.cland.alice.tool.gateway.engine.ExecutionEngine;
import org.cland.alice.tool.gateway.engine.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 工具调用分派策略。
 *
 * <p>处理 {@link Action.Type#TOOL_CALL} 类型的动作：
 *
 * <ol>
 *   <li>通过 {@link ExecutionEngine}（或 {@link GuardrailToolProxy}）执行工具
 *   <li>处理 read_file 缓存跳过
 *   <li>通过 {@link AgentEventBus} 广播事件
 *   <li>记录 WAL
 *   <li>返回后续 LLM 调用的 Action（tool result 作为 user content）
 * </ol>
 *
 * <p>遵循开闭原则（OCP）：通过 {@link MicroReActEngine#registerStrategy} 注入。
 */
public class ToolDispatchStrategy implements DispatchStrategy {

  private static final Logger logger = LoggerFactory.getLogger(ToolDispatchStrategy.class);

  private final AgentFacade agent;
  private final Vertx vertx;
  private final WalSession wal;
  private final AgentEventBus eventBus;

  private volatile ExecutionEngine executionEngine;
  private volatile GuardrailToolProxy guardrailToolProxy;

  public ToolDispatchStrategy(
      AgentFacade agent, Vertx vertx, WalSession wal, AgentEventBus eventBus) {
    this.agent = Objects.requireNonNull(agent, "agent must not be null");
    this.vertx = Objects.requireNonNull(vertx, "vertx must not be null");
    this.wal = wal;
    this.eventBus = eventBus != null ? eventBus : new AgentEventBus();
  }

  /** 注入 {@link ExecutionEngine}。 */
  public ToolDispatchStrategy withExecutionEngine(ExecutionEngine engine) {
    this.executionEngine = Objects.requireNonNull(engine, "executionEngine must not be null");
    return this;
  }

  /** 注入 {@link GuardrailToolProxy}。 */
  public ToolDispatchStrategy withGuardrailToolProxy(GuardrailToolProxy proxy) {
    this.guardrailToolProxy = Objects.requireNonNull(proxy, "guardrailToolProxy must not be null");
    return this;
  }

  @Override
  public boolean supports(Action action) {
    return action.type() == Action.Type.TOOL_CALL;
  }

  @Override
  public Future<StepWithContext> execute(AgentContext ctx, Action action) {
    eventBus.fireOnAction(action.target(), action.parameters());
    logger.info("[ToolDispatch] target={} params={}", action.target(), action.parameters());

    if (agent.toolRegistry() == null) {
      return Future.succeededFuture(
          new StepWithContext(
              ctx,
              new StepResult.Continue(
                  Action.revision("No ToolRegistry for tool: " + action.target()),
                  Observation.failure("ToolRegistry not configured"))));
    }

    // read_file 缓存检查
    if ("read_file".equals(action.target())) {
      Object pathObj = action.parameters().get("path");
      if (pathObj instanceof String path && !path.isBlank()) {
        @SuppressWarnings("unchecked")
        Set<String> readFiles = (Set<String>) ctx.get("__read_files");
        if (readFiles != null && readFiles.contains(path)) {
          logger.info("[ToolDispatch] read_file skipped (cached): {}", path);
          return Future.succeededFuture(
              new StepWithContext(
                  ctx,
                  new StepResult.Continue(
                      null, Observation.success("[CACHED] " + path + " was already read."))));
        }
      }
    }

    Promise<StepWithContext> promise = Promise.promise();

    // WAL: 记录工具调用
    if (wal != null) {
      wal.assistantToolCalls(
          ctx.sessionId(),
          java.util.List.of(
              org.cland.alice.core.agent.wal.ToolCall.of(
                  action.actionId(), action.target(), action.parameters())));
    }

    vertx
        .<StepResult>executeBlocking(() -> executeTool(ctx, action), false)
        .onComplete(
            ar -> {
              if (ar.succeeded()) {
                promise.complete(new StepWithContext(ctx, ar.result()));
              } else {
                promise.fail(ar.cause());
              }
            });

    return promise.future();
  }

  private StepResult executeTool(AgentContext ctx, Action action) {
    try {
      // 惰性初始化 ExecutionEngine
      if (executionEngine == null) {
        synchronized (this) {
          if (executionEngine == null) {
            if (agent.toolRegistry() != null) {
              executionEngine = ExecutionEngine.builder().registry(agent.toolRegistry()).build();
              logger.info("[ToolDispatch] ExecutionEngine lazily initialized");
            } else {
              return new StepResult.Continue(
                  Action.revision("No ExecutionEngine for tool: " + action.target()),
                  Observation.failure("ExecutionEngine not configured"));
            }
          }
        }
      }

      ToolResult result;
      if (guardrailToolProxy != null) {
        result = guardrailToolProxy.invoke(action.target(), action.parameters());
      } else {
        result = executionEngine.invoke(action.target(), action.parameters());
      }

      boolean success = result.status() == ToolResult.Status.SUCCESS;
      logger.info("[ToolDispatch] {} result status={}", action.target(), result.status());

      // 广播观察事件
      String rawData = result.rawData();
      String summary = result.summary();
      eventBus.fireOnObserve(
          rawData != null && !rawData.isBlank() ? rawData : (summary != null ? summary : "(empty)"),
          summary != null ? summary : "",
          0L);

      // WAL: 记录结果
      if (wal != null) {
        String resultContent =
            rawData != null && !rawData.isBlank() ? rawData : (summary != null ? summary : "");
        wal.toolResult(ctx.sessionId(), action.actionId(), resultContent);
        wal.checkpointOnToolReturn(ctx.sessionId(), action.target(), success);
      }

      if (success) {
        return handleSuccess(ctx, action, result);
      } else {
        return new StepResult.Continue(
            Action.revision("Tool failed: " + action.target() + " - " + summary),
            Observation.failure("Tool " + action.target() + ": " + summary));
      }
    } catch (Exception e) {
      if (wal != null) {
        wal.toolResult(ctx.sessionId(), action.actionId(), "[Tool Error: " + e.getMessage() + "]");
        wal.checkpointOnError(ctx.sessionId(), "TOOL_ERROR", e.getMessage());
      }
      return new StepResult.Failure("Tool call error: " + e.getMessage());
    }
  }

  private StepResult handleSuccess(AgentContext ctx, Action action, ToolResult result) {
    // 跟踪已读取的文件
    if ("read_file".equals(action.target())) {
      Object pathObj = action.parameters().get("path");
      if (pathObj instanceof String path && !path.isBlank()) {
        @SuppressWarnings("unchecked")
        Set<String> readFiles = (Set<String>) ctx.get("__read_files");
        if (readFiles == null) {
          readFiles = new java.util.HashSet<>();
          ctx.put("__read_files", readFiles);
        }
        readFiles.add(path);
      }
    }

    // 检查是否还有未消耗的结构化 tool_calls
    boolean hasMoreMarkers = hasMoreToolCalls(ctx);
    if (hasMoreMarkers) {
      return new StepResult.Continue(
          null, Observation.success("Tool " + action.target() + " succeeded: " + result.summary()));
    }

    String toolResultContent =
        result.rawData() != null && !result.rawData().isBlank()
            ? result.rawData()
            : result.summary();

    // 累积工具执行日志
    accumulateActionLog(ctx, action, toolResultContent);

    // 构建 Micro User Content
    String rawPrompt = ctx.containsKey("prompt") ? ctx.get("prompt").toString() : "";
    @SuppressWarnings("unchecked")
    Set<String> readFiles = (Set<String>) ctx.get("__read_files");
    String userContent =
        PromptManager.buildMicroUserContent(
            ctx.get("__action_log").toString(), rawPrompt, readFiles);

    return new StepResult.Continue(
        Action.llmInference(agent.config().defaultModelId(), userContent),
        Observation.success("Tool " + action.target() + " succeeded: " + result.summary()));
  }

  private boolean hasMoreToolCalls(AgentContext ctx) {
    Object rawTc = ctx.get("__tool_calls");
    if (rawTc instanceof java.util.List<?> tcList && !tcList.isEmpty()) {
      int currentIdx =
          ctx.containsKey("__tool_call_index")
              ? Integer.parseInt(ctx.get("__tool_call_index").toString())
              : 0;
      return currentIdx < tcList.size();
    }
    return false;
  }

  private void accumulateActionLog(AgentContext ctx, Action action, String content) {
    StringBuilder actionLogBuilder = new StringBuilder();
    if (ctx.containsKey("__action_log")) {
      actionLogBuilder.append(ctx.get("__action_log").toString());
      String existing = actionLogBuilder.toString();
      int idx = existing.lastIndexOf("\n\n", existing.length() - 3);
      if (idx > 0 && existing.length() > 2000) {
        actionLogBuilder = new StringBuilder(existing.substring(idx + 2));
      }
    }
    if ("write_file".equals(action.target())) {
      actionLogBuilder.append("Tool " + action.target() + " succeeded.\n\n");
    } else {
      actionLogBuilder.append("Tool " + action.target() + " returned:\n" + content + "\n\n");
    }
    ctx.put("__action_log", actionLogBuilder.toString());
  }
}
