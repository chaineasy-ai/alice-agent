package org.cland.alice.core.agent.executor;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import org.cland.alice.core.agent.AgentConfig;
import org.cland.alice.core.agent.AgentContext;
import org.cland.alice.core.agent.AgentFacade;
import org.cland.alice.core.agent.guardrail.GuardrailToolProxy;
import org.cland.alice.core.agent.lifecycle.Action;
import org.cland.alice.core.agent.lifecycle.Observation;
import org.cland.alice.core.agent.lifecycle.StepWithContext;
import org.cland.alice.core.agent.prompt.PromptManager;
import org.cland.alice.core.agent.result.StepResult;
import org.cland.alice.core.agent.wal.SnowflakeIdGenerator;
import org.cland.alice.core.agent.wal.WalSession;
import org.cland.alice.model.Call;
import org.cland.alice.tool.gateway.engine.ExecutionEngine;
import org.cland.alice.tool.gateway.engine.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Micro-ReAct 战术执行引擎。
 *
 * <p>遵循单一职责原则（SRP）：仅负责 Micro-ReAct（Reason → Dispatch → Observe）战术循环的执行。 从 {@link AgentExecutor}
 * 中提取，使其聚焦于 PPAO 宏观编排。
 *
 * <p>使用策略模式（{@link DispatchStrategy}）处理 LLM 推理和工具调用的分派， 遵循开闭原则（OCP）— 新增分派类型无需修改本引擎。
 *
 * <pre>
 *   Micro-ReAct Loop:
 *     Reason → Dispatch → Observe → (loop until sub-goal done or circuit break)
 * </pre>
 */
public class MicroReActEngine {

  private static final Logger logger = LoggerFactory.getLogger(MicroReActEngine.class);

  private final AgentFacade agent;
  private final AgentConfig config;
  private final Vertx vertx;
  private WalSession wal;
  private final AgentEventBus eventBus;
  private final List<DispatchStrategy> strategies;

  private volatile ExecutionEngine executionEngine;
  private volatile GuardrailToolProxy guardrailToolProxy;

  /**
   * 创建 Micro-ReAct 引擎。
   *
   * @param agent Agent 实例（用于获取 toolRegistry 等）
   * @param vertx Vert.x 实例
   * @param wal 可选的 WAL 会话
   * @param eventBus 事件总线
   */
  public MicroReActEngine(AgentFacade agent, Vertx vertx, WalSession wal, AgentEventBus eventBus) {
    this.agent = Objects.requireNonNull(agent, "agent must not be null");
    this.config = agent.config();
    this.vertx = Objects.requireNonNull(vertx, "vertx must not be null");
    this.wal = wal;
    this.eventBus = eventBus != null ? eventBus : new AgentEventBus();
    this.strategies = new ArrayList<>();
    // 注册默认分派策略（OCP：新的分派类型通过 registerStrategy 添加，无需修改引擎）
    registerStrategy(new LlmDispatchStrategy(agent, vertx, wal, eventBus));
    registerStrategy(new ToolDispatchStrategy(agent, vertx, wal, eventBus));
  }

  /**
   * 注入或更新 {@link WalSession}，用于 WAL 持久化。
   *
   * @param walSession WalSession 实例
   * @return this（链式调用）
   */
  public MicroReActEngine withWal(WalSession walSession) {
    if (walSession != null) {
      this.wal = walSession;
      logger.info("[MicroReActEngine] WAL session updated");
    }
    return this;
  }

  /**
   * 注入 {@link ExecutionEngine}，用于工具执行。
   *
   * @param engine ExecutionEngine 实例
   * @return this（链式调用）
   */
  public MicroReActEngine withExecutionEngine(ExecutionEngine engine) {
    this.executionEngine = Objects.requireNonNull(engine, "executionEngine must not be null");
    return this;
  }

  /**
   * 注入 {@link GuardrailToolProxy}，为每个 TOOL_CALL 启用 Guardrail 预检/后检。
   *
   * @param proxy GuardrailToolProxy 实例
   * @return this（链式调用）
   */
  public MicroReActEngine withGuardrailToolProxy(GuardrailToolProxy proxy) {
    this.guardrailToolProxy = Objects.requireNonNull(proxy, "guardrailToolProxy must not be null");
    return this;
  }

  /**
   * 注册自定义分派策略。
   *
   * @param strategy 分派策略实现
   * @return this（链式调用）
   */
  public MicroReActEngine registerStrategy(DispatchStrategy strategy) {
    if (strategy != null) {
      this.strategies.add(strategy);
    }
    return this;
  }

  /**
   * 执行 Micro-ReAct 循环。
   *
   * @param ctx 当前 Agent 上下文
   * @param initialAction 初始 Action（通常是 LLM_INFERENCE 或 TOOL_CALL）
   * @return 循环完成后的 StepWithContext（包含最终结果）
   */
  public Future<StepWithContext> execute(AgentContext ctx, Action initialAction) {
    final int maxMicroIterations = config.maxMicroDepth();
    final String originalPrompt = ctx.containsKey("prompt") ? ctx.get("prompt").toString() : "";

    // 缓存 Micro-ReAct 系统 prompt
    ctx.put("__micro_system_prompt", PromptManager.buildMicroLoopSystemPrompt());

    return microReActStep(ctx, initialAction, originalPrompt, 0, maxMicroIterations);
  }

  // ========================================================================
  // Micro-ReAct 递归步骤
  // ========================================================================

  /** Micro-ReAct 单步递归：执行 Action → 观察 → 推理下一步 → 递归/终止。 */
  private Future<StepWithContext> microReActStep(
      AgentContext ctx, Action currentAction, String originalPrompt, int depth, int maxDepth) {

    logger.debug("[Micro-ReAct] step depth={}/{} action={}", depth, maxDepth, currentAction);

    // 熔断检查
    if (depth >= maxDepth) {
      return handleCircuitBreaker(ctx, maxDepth, depth);
    }

    // === Dispatch (执行) ===
    Future<StepWithContext> dispatchFuture = dispatch(ctx, currentAction);

    return dispatchFuture.compose(
        stepResult -> {
          AgentContext updatedCtx = stepResult.context();
          StepResult result = stepResult.result();

          // === Observe (观察结果) ===
          if (result instanceof StepResult.Finish || result instanceof StepResult.Failure) {
            return Future.succeededFuture(stepResult);
          }

          // 提取观察
          Observation obs = stepResult.observation();
          if (obs != null) {
            updatedCtx.appendThought("[Micro-ReAct] Observed: " + obs.summary());
            updatedCtx.put("lastObservation", obs);
            updatedCtx.put("lastActionResult", obs.summary());
          }

          // === Reason (基于观察推理下一步微意图) ===
          Action continueAction = result instanceof StepResult.Continue c ? c.nextAction() : null;

          if (continueAction != null
              && continueAction.type() != Action.Type.FINISH
              && continueAction.type() != Action.Type.REVISION) {
            // 清理旧状态
            updatedCtx.remove("__tool_calls");
            updatedCtx.remove("__tool_call_index");
            updatedCtx.remove("__finish_reason");
            updatedCtx.remove("__turn_end");
            updatedCtx.remove("__true_start");
            logger.debug(
                "[Micro-ReAct/Reason] dispatching follow-up: type={} target={} depth={}",
                continueAction.type(),
                continueAction.target(),
                depth);
            return microReActStep(updatedCtx, continueAction, originalPrompt, depth + 1, maxDepth);
          }

          // 检查是否有结构化 tool_calls 需要并行分派
          Object rawToolCalls = updatedCtx.get("__tool_calls");
          String finishReason =
              updatedCtx.containsKey("__finish_reason")
                  ? updatedCtx.get("__finish_reason").toString()
                  : "stop";

          if (rawToolCalls instanceof List<?> tcList && !tcList.isEmpty()) {
            return handleParallelToolCalls(updatedCtx, tcList, originalPrompt, depth, maxDepth);
          }

          // 根据 finish_reason 判断
          return handleFinishReason(updatedCtx, finishReason);
        });
  }

  // ========================================================================
  // Dispatch (分派)
  // ========================================================================

  /** 根据 Action 类型选择分派策略并执行。 */
  private Future<StepWithContext> dispatch(AgentContext ctx, Action action) {
    // 先尝试注册的自定义策略
    for (DispatchStrategy strategy : strategies) {
      if (strategy.supports(action)) {
        return strategy.execute(ctx, action);
      }
    }

    // 没有注册的策略支持此 Action — 按原样传递回 Macro 循环处理
    logger.warn("[MicroReAct] No strategy supports action type={}, passing through", action.type());
    return Future.succeededFuture(new StepWithContext(ctx, new StepResult.Continue(action)));
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // LLM/Tool dispatch 由 DispatchStrategy 实现（已通过 registerStrategy 自动注册）
  // 新增分派类型：实现 DispatchStrategy 接口，调用 registerStrategy() 注入
  // ═══════════════════════════════════════════════════════════════════════════

  // ═══════════════════════════════════════════════════════════════════════════
  // 并行工具调用处理
  // ═══════════════════════════════════════════════════════════════════════════

  /** 并行分派多个结构化 tool_calls（来自 Function Calling）。 */
  @SuppressWarnings("unchecked")
  private Future<StepWithContext> handleParallelToolCalls(
      AgentContext ctx, List<?> tcList, String originalPrompt, int depth, int maxDepth) {

    // 惰性初始化 ExecutionEngine
    if (executionEngine == null) {
      synchronized (this) {
        if (executionEngine == null && agent.toolRegistry() != null) {
          executionEngine = ExecutionEngine.builder().registry(agent.toolRegistry()).build();
          logger.info("[Micro-ReAct/Tool] ExecutionEngine lazily initialized (parallel)");
        }
      }
    }
    if (executionEngine == null) {
      return Future.succeededFuture(
          new StepWithContext(
              ctx,
              new StepResult.Continue(
                  Action.revision("No ExecutionEngine for parallel tool dispatch"),
                  Observation.failure("ExecutionEngine not configured"))));
    }

    List<Call.ToolCall> toolCalls = (List<Call.ToolCall>) tcList;
    Set<String> readFiles = (Set<String>) ctx.get("__read_files");

    var virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
    List<CompletableFuture<ParallelToolResult>> parallelFutures = new ArrayList<>();

    for (Call.ToolCall tc : toolCalls) {
      Map<String, Object> params = parseToolArgsJson(tc.arguments());

      // read_file 缓存跳过
      if ("read_file".equals(tc.name()) && readFiles != null) {
        Object pathObj = params.get("path");
        if (pathObj instanceof String path && readFiles.contains(path)) {
          parallelFutures.add(
              CompletableFuture.completedFuture(
                  new ParallelToolResult(
                      tc,
                      params,
                      ToolResult.builder()
                          .status(ToolResult.Status.SUCCESS)
                          .summary("[CACHED] " + path + " was already read.")
                          .rawData("[CACHED] " + path)
                          .metadata(Map.of("toolName", tc.name(), "cached", "true"))
                          .build(),
                      true)));
          continue;
        }
      }

      // WAL 记录
      recordParallelToolCallToWal(ctx, tc, params);

      parallelFutures.add(
          CompletableFuture.supplyAsync(
              () -> {
                try {
                  ToolResult r =
                      guardrailToolProxy != null
                          ? guardrailToolProxy.invoke(tc.name(), params)
                          : executionEngine.invoke(tc.name(), params);
                  return new ParallelToolResult(tc, params, r, false);
                } catch (Exception e) {
                  return new ParallelToolResult(
                      tc,
                      params,
                      ToolResult.failure(tc.name() + " error: " + e.getMessage()),
                      false);
                }
              },
              virtualExecutor));
    }

    Promise<StepWithContext> parallelPromise = Promise.promise();
    CompletableFuture.allOf(parallelFutures.toArray(new CompletableFuture[0]))
        .whenComplete(
            (v, err) -> {
              if (err != null) {
                parallelPromise.fail(err);
                return;
              }

              AgentContext updatedCtx = ctx;
              StringBuilder batchLog = new StringBuilder();
              Set<String> updatedReadFiles = (Set<String>) updatedCtx.get("__read_files");

              for (var future : parallelFutures) {
                ParallelToolResult result = future.join();

                if (!result.cached) {
                  boolean ok = result.result.status() == ToolResult.Status.SUCCESS;
                  eventBus.fireOnAction(result.toolCall.name(), result.params);
                  String rawData = result.result.rawData();
                  String summary = result.result.summary();
                  eventBus.fireOnObserve(
                      rawData != null && !rawData.isBlank()
                          ? rawData
                          : (summary != null ? summary : ""),
                      summary != null ? summary : "",
                      0L);

                  recordParallelToolResultToWal(updatedCtx, result, ok);
                  trackReadFileParallel(updatedCtx, result);

                  if (!"write_file".equals(result.toolCall.name())) {
                    batchLog
                        .append("Tool ")
                        .append(result.toolCall.name())
                        .append(" returned:\n")
                        .append(
                            rawData != null && !rawData.isBlank()
                                ? rawData
                                : (summary != null ? summary : ""))
                        .append("\n\n");
                  } else {
                    batchLog
                        .append("Tool ")
                        .append(result.toolCall.name())
                        .append(" succeeded.\n\n");
                  }
                } else {
                  eventBus.fireOnAction(result.toolCall.name(), result.params);
                  eventBus.fireOnObserve(
                      "[CACHED] " + result.toolCall.name() + " was already read.",
                      "[CACHED] " + result.toolCall.name(),
                      0L);
                  batchLog.append("[CACHED] ").append(result.toolCall.name()).append("\n\n");
                }
              }

              updatedCtx.remove("__tool_calls");
              updatedCtx.remove("__tool_call_index");
              updatedCtx.remove("__finish_reason");
              updatedCtx.remove("__turn_end");
              updatedCtx.remove("__true_start");

              String actionLog = batchLog.toString();
              if (!actionLog.isBlank()) {
                updatedCtx.put("__action_log", actionLog);
                String rawPrompt =
                    updatedCtx.containsKey("prompt") ? updatedCtx.get("prompt").toString() : "";
                String userContent =
                    PromptManager.buildMicroUserContent(actionLog, rawPrompt, updatedReadFiles);
                microReActStep(
                        updatedCtx,
                        Action.llmInference(config.defaultModelId(), userContent),
                        originalPrompt,
                        depth + 1,
                        maxDepth)
                    .onSuccess(parallelPromise::complete)
                    .onFailure(parallelPromise::fail);
                return;
              }

              parallelPromise.complete(
                  new StepWithContext(updatedCtx, new StepResult.Continue(null)));
            });

    return parallelPromise.future();
  }

  // ========================================================================
  // 辅助方法
  // ========================================================================

  /** 熔断处理 */
  private Future<StepWithContext> handleCircuitBreaker(AgentContext ctx, int maxDepth, int depth) {
    logger.warn("[Micro-ReAct] circuit breaker triggered at depth={}", depth);
    ctx.appendThought("[Micro-ReAct] Circuit breaker: max depth reached");

    if (wal != null) {
      wal.checkpointOnError(
          ctx.sessionId(), "CIRCUIT_BREAKER", "Micro-ReAct circuit breaker at depth " + depth);
    }

    String actionLog = ctx.containsKey("__action_log") ? ctx.get("__action_log").toString() : "";
    if (!actionLog.isBlank()) {
      ctx.put(
          "__system_event",
          "[System] Circuit breaker: max depth ("
              + maxDepth
              + ") reached after "
              + actionLog.split("\n\n").length
              + " tool calls");
      ctx.put("lastActionResult", "Micro-ReAct completed with " + depth + " steps");
      ctx.put("result", actionLog);

      if (wal != null) {
        wal.checkpointOnReActEnd(ctx.sessionId(), "ACTING_FINISHED", ctx.asMap(), actionLog);
      }

      return Future.succeededFuture(
          new StepWithContext(
              ctx,
              new StepResult.Finish(
                  actionLog,
                  "Micro-ReAct circuit breaker at depth "
                      + depth
                      + " with "
                      + actionLog.length()
                      + " chars")));
    }

    return Future.succeededFuture(
        new StepWithContext(ctx, new StepResult.Continue(Action.finish())));
  }

  /** 处理 finish_reason */
  private Future<StepWithContext> handleFinishReason(AgentContext ctx, String finishReason) {
    logger.info(
        "[Micro-ReAct/Reason] finish_reason={} responseLength={}",
        finishReason,
        ctx.containsKey("result") ? ctx.get("result").toString().length() : 0);

    if ("stop".equals(finishReason) || "tool_calls".equals(finishReason)) {
      String llmOutput = ctx.containsKey("result") ? ctx.get("result").toString() : "";
      if (!llmOutput.isEmpty()) {
        ctx.put("lastObservation", Observation.success(llmOutput));
        ctx.put("lastActionResult", llmOutput);
      }
      if (wal != null) {
        wal.checkpointOnReActEnd(ctx.sessionId(), "ACTING_FINISHED", ctx.asMap(), "");
      }
      return Future.succeededFuture(
          new StepWithContext(
              ctx,
              new StepResult.Finish(
                  llmOutput, "Micro-ReAct completed: finish_reason=" + finishReason)));
    }

    logger.warn("[Micro-ReAct/Reason] Non-success finish_reason={}", finishReason);
    String llmOutput = ctx.containsKey("result") ? ctx.get("result").toString() : "";
    if (wal != null) {
      wal.checkpointOnError(
          ctx.sessionId(), "FINISH_REASON_" + finishReason.toUpperCase(), llmOutput);
    }
    return Future.succeededFuture(
        new StepWithContext(ctx, new StepResult.Failure("LLM finished: " + finishReason)));
  }

  /** 构建 LLM 调用参数 */
  private Map<String, Object> buildCallParams(Action action) {
    Map<String, Object> callParams = new java.util.LinkedHashMap<>();
    if (action.parameters() != null) {
      for (var entry : action.parameters().entrySet()) {
        String k = entry.getKey();
        if ("enable_thinking".equals(k) || "reasoning_effort".equals(k)) {
          callParams.put(k, entry.getValue());
        }
      }
    }
    return callParams;
  }

  /** 附加 tools schema */
  private void attachToolsSchema(Map<String, Object> callParams) {
    if (agent.toolRegistry() != null) {
      try {
        var allTools = agent.toolRegistry().allTools();
        if (!allTools.isEmpty()) {
          var tools =
              allTools.stream()
                  .<Map<String, Object>>map(
                      meta -> {
                        var function = new java.util.LinkedHashMap<String, Object>();
                        function.put("name", meta.name());
                        function.put("description", meta.description());
                        function.put("parameters", meta.inputSchema());
                        var tool = new java.util.LinkedHashMap<String, Object>();
                        tool.put("type", "function");
                        tool.put("function", function);
                        return tool;
                      })
                  .collect(java.util.stream.Collectors.toList());
          callParams.put("tools", tools);
        }
      } catch (Exception e) {
        logger.warn("[Micro-ReAct/LLM] Failed to generate tools schema", e);
      }
    }
  }

  /** 跟踪已读取的文件 */
  private void trackReadFile(AgentContext ctx, Action action) {
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
  }

  /** 跟踪并行工具调用中的文件读取 */
  private void trackReadFileParallel(AgentContext ctx, ParallelToolResult ptr) {
    if ("read_file".equals(ptr.toolCall.name())) {
      Object pathObj = ptr.params.get("path");
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
  }

  /** 累积工具执行日志 */
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

  /** 检查是否还有未消耗的 structured tool_calls */
  private boolean hasMoreToolCalls(AgentContext ctx) {
    Object rawTc = ctx.get("__tool_calls");
    if (rawTc instanceof List<?> tcList && !tcList.isEmpty()) {
      int currentIdx =
          ctx.containsKey("__tool_call_index")
              ? Integer.parseInt(ctx.get("__tool_call_index").toString())
              : 0;
      return currentIdx < tcList.size();
    }
    return false;
  }

  // ========================================================================
  // WAL 记录辅助方法
  // ========================================================================

  private void recordParallelToolCallToWal(
      AgentContext ctx, Call.ToolCall tc, Map<String, Object> params) {
    if (wal == null) return;
    wal.assistantToolCalls(
        ctx.sessionId(),
        List.of(
            org.cland.alice.core.agent.wal.ToolCall.of(
                String.valueOf(SnowflakeIdGenerator.getInstance().nextId()), tc.name(), params)));
  }

  private void recordParallelToolResultToWal(
      AgentContext ctx, ParallelToolResult ptr, boolean success) {
    if (wal == null) return;
    String rawData = ptr.result.rawData();
    String summary = ptr.result.summary();
    String rc = rawData != null && !rawData.isBlank() ? rawData : (summary != null ? summary : "");
    wal.toolResult(
        ctx.sessionId(), String.valueOf(SnowflakeIdGenerator.getInstance().nextId()), rc);
    wal.checkpointOnToolReturn(ctx.sessionId(), ptr.toolCall.name(), success);
  }

  // ========================================================================
  // 原始响应解析辅助方法
  // ========================================================================

  /** 从 Call.Response 的 raw metadata 中提取 reasoning_content。 */
  static String extractReasoningFromRaw(Call.Response response) {
    if (response == null || response.metadata() == null) return "";
    Object raw = response.metadata().get("raw");
    if (raw == null) return "";
    String rawStr = raw.toString();
    int idx = rawStr.indexOf("\"reasoning_content\":\"");
    if (idx < 0) return "";
    idx += 21;
    StringBuilder sb = new StringBuilder();
    while (idx < rawStr.length()) {
      char c = rawStr.charAt(idx);
      if (c == '\\' && idx + 1 < rawStr.length()) {
        sb.append(rawStr.charAt(idx + 1));
        idx += 2;
      } else if (c == '"') {
        break;
      } else {
        sb.append(c);
        idx++;
      }
    }
    return sb.toString();
  }

  /** 从 Call.Response 的 raw metadata 中提取 finish_reason。 */
  static String extractFinishReasonFromRaw(Call.Response response) {
    if (response == null || response.metadata() == null) return "stop";
    Object raw = response.metadata().get("raw");
    if (raw == null) return "stop";
    String rawStr = raw.toString();
    int idx = rawStr.indexOf("\"finish_reason\":\"");
    if (idx < 0) return "stop";
    idx += 17;
    StringBuilder sb = new StringBuilder();
    while (idx < rawStr.length()) {
      char c = rawStr.charAt(idx);
      if (c == '"') break;
      sb.append(c);
      idx++;
    }
    return sb.toString();
  }

  /** 解析 LLM Function Calling 返回的 JSON arguments。 */
  static Map<String, Object> parseToolArgsJson(String json) {
    Map<String, Object> result = new java.util.LinkedHashMap<>();
    if (json == null || json.isBlank()) return result;
    try {
      com.fasterxml.jackson.databind.ObjectMapper mapper =
          new com.fasterxml.jackson.databind.ObjectMapper();
      com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(json);
      if (root.isObject()) {
        var it = root.fieldNames();
        while (it.hasNext()) {
          String key = it.next();
          com.fasterxml.jackson.databind.JsonNode val = root.get(key);
          if (val.isTextual()) {
            result.put(key, val.asText());
          } else if (val.isNumber()) {
            result.put(key, val.asText());
          } else if (val.isBoolean()) {
            result.put(key, val.asText());
          } else if (!val.isNull()) {
            result.put(key, val.toString());
          }
        }
      }
    } catch (Exception e) {
      logger.warn(
          "[ToolArgsParser] Failed to parse JSON: {} - {}",
          e.getMessage(),
          json.substring(0, Math.min(200, json.length())));
    }
    return result;
  }

  // ========================================================================
  // 内部类型
  // ========================================================================

  /** 并行工具调用结果 */
  private record ParallelToolResult(
      Call.ToolCall toolCall, Map<String, Object> params, ToolResult result, boolean cached) {}
}
