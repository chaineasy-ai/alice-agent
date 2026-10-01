package org.cland.alice.core.agent.graph;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.cland.alice.core.agent.kernel.EventStream;
import org.cland.alice.core.agent.kernel.InferRequest.ToolSpec;
import org.cland.alice.core.agent.kernel.Inferencer;
import org.cland.alice.core.agent.kernel.KernelState;
import org.cland.alice.core.agent.kernel.Loop;
import org.cland.alice.core.agent.kernel.SessionRequest;
import org.cland.alice.core.agent.kernel.SessionResult;
import org.cland.alice.core.agent.kernel.SessionStatus;
import org.cland.alice.core.agent.kernel.graph.Decision;
import org.cland.alice.core.agent.kernel.graph.DecisionKind;
import org.cland.alice.core.agent.kernel.graph.EffectOutcome;
import org.cland.alice.core.agent.kernel.graph.GraphNode;
import org.cland.alice.core.agent.kernel.graph.KernelTraceListener;
import org.cland.alice.core.agent.kernel.graph.R0Interpreter;
import org.cland.alice.core.agent.kernel.graph.SafePoint;
import org.cland.alice.core.agent.kernel.graph.SessionOutcome;
import org.cland.alice.core.agent.kernel.graph.StandardSkeleton;
import org.cland.alice.core.agent.kernel.graph.ToolCallReq;
import org.cland.alice.core.agent.wal.GraphCheckpointAdapter;
import org.cland.alice.core.agent.wal.WalStore;
import org.cland.alice.core.planner.PlannerService;
import org.cland.alice.tool.gateway.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 图会话内核 —— 内核执行契约 {@link Loop} 的生产实现（换轨目标态）。
 *
 * <p>每会话装配一次标准骨架（规划 → TAO → 反思，§5.3）： STRATEGIZE = {@link PlannerGoalBrain}（PlannerService
 * 审慎后端）；TAO Thought = {@link LlmActorBrain} （Inferencer 语义契约 + 当前模型）；TAO Action = {@link
 * ToolRegistryEffectGateway}（ExecutionEngine）。 仲裁与 verifyPost 判据由装配方注入（D8：判据内容维持原实现语义 = 模型判据提交 +
 * 规则后检）。
 *
 * <p>会话结果收敛：answer 取账本产物槽位（模型最终回答经写点 ① 落账，等效 legacy ctx.result）； 状态映射 FINISHED/FAILED（brain
 * 异常与图结构性失败均收敛为 FAILED，不向调用方抛错）。
 *
 * <p>说明：事件流桥接（thought/action/observe 埋点 → EventStream）与 WAL/Checkpoint 接入 随换轨接线一并落地；本实现先满足执行契约面。
 */
public final class GraphSessionKernel implements Loop {

  private static final Logger logger = LoggerFactory.getLogger(GraphSessionKernel.class);

  /** 会话级步数预算（图遍历步数单一来源，P4）。 */
  private static final int DEFAULT_MAX_STEPS = 10_000;

  private final Vertx vertx;
  private final PlannerService planner;
  private final ToolRegistry toolRegistry;
  private final Inferencer inferencer;
  private final String defaultModelId;
  private final String actorSystemPrompt;
  private final StandardSkeleton.ArbitrationBrain arbitration;
  private final org.cland.alice.core.agent.kernel.graph.GatePolicy verifyPost;
  private final int revisionBudget;
  private final int taoEffectBudget;

  /** 取消标志（安全点语义：下个 execute 前生效）。 */
  private volatile boolean cancelled;

  /** 可选 WAL/Checkpoint 存储（§3.2 ⑥）。为 null 时图内核行为与现状完全一致。 */
  private volatile WalStore walStore;

  private volatile String lastSessionId;
  private volatile SessionOutcome lastOutcome;
  private volatile int lastMaxSteps = DEFAULT_MAX_STEPS;

  /** 事件订阅面（thought/action/observe 语义由 trace 埋点翻译）。 */
  private final KernelEventStream eventStream = new KernelEventStream();

  public GraphSessionKernel(
      Vertx vertx,
      PlannerService planner,
      ToolRegistry toolRegistry,
      Inferencer inferencer,
      String defaultModelId,
      String actorSystemPrompt,
      StandardSkeleton.ArbitrationBrain arbitration,
      org.cland.alice.core.agent.kernel.graph.GatePolicy verifyPost,
      int revisionBudget,
      int taoEffectBudget) {
    this.vertx = Objects.requireNonNull(vertx, "vertx");
    this.planner = Objects.requireNonNull(planner, "planner");
    this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry");
    this.inferencer = Objects.requireNonNull(inferencer, "inferencer");
    this.defaultModelId = Objects.requireNonNull(defaultModelId, "defaultModelId");
    this.actorSystemPrompt = actorSystemPrompt;
    this.arbitration =
        arbitration != null
            ? arbitration
            : (g, l, o) -> StandardSkeleton.Arbitration.pass(); // D8 判据内容由装配方提供，默认放行
    this.verifyPost = verifyPost;
    this.revisionBudget = revisionBudget > 0 ? revisionBudget : 2;
    this.taoEffectBudget = taoEffectBudget > 0 ? taoEffectBudget : 10;
  }

  @Override
  public Future<SessionResult> execute(SessionRequest request) {
    Objects.requireNonNull(request, "request");
    String sessionId =
        request.sessionId() != null && !request.sessionId().isBlank()
            ? request.sessionId()
            : UUID.randomUUID().toString().substring(0, 8);

    if (cancelled) {
      logger.warn("[GraphSessionKernel] cancelled before session start");
      return Future.succeededFuture(
          new SessionResult(SessionStatus.CANCELLED, sessionId, "", 0, Map.of()));
    }

    String input = request.input() != null ? request.input() : "";
    String modelId = request.modelId() != null ? request.modelId() : defaultModelId;
    int maxSteps = maxStepsOf(request);
    this.lastSessionId = sessionId;
    this.lastMaxSteps = maxSteps;

    return vertx
        .executeBlocking(() -> runSync(sessionId, input, modelId, maxSteps))
        .map(
            outcome -> {
              this.lastOutcome = outcome;
              return toResult(sessionId, outcome);
            })
        .otherwise(
            err -> {
              // 异常也收敛为 FAILED outcome，保证 state()/lastOutcome 单一真相
              logger.error("[GraphSessionKernel] session {} failed", sessionId, err);
              Throwable root = err;
              while (root.getCause() != null && root.getCause().getMessage() != null) {
                root = root.getCause();
              }
              String message =
                  root.getMessage() != null ? root.getMessage() : root.getClass().getSimpleName();
              SessionOutcome failed =
                  new SessionOutcome(SessionOutcome.Status.FAILED, message, 0, List.of(), Map.of());
              this.lastOutcome = failed;
              return toResult(sessionId, failed);
            });
  }

  @Override
  public void cancel() {
    this.cancelled = true;
    logger.info("[GraphSessionKernel] cancel requested");
  }

  @Override
  public KernelState state() {
    SessionOutcome outcome = lastOutcome;
    if (outcome == null) {
      return new KernelState(lastSessionId, "IDLE", 0, lastMaxSteps, cancelled);
    }
    String phase =
        switch (outcome.status()) {
          case FINISHED -> "FINISH";
          case CANCELLED -> "CANCELLED";
          case FAILED -> "FAILED";
        };
    return new KernelState(lastSessionId, phase, outcome.steps(), lastMaxSteps, cancelled);
  }

  @Override
  public EventStream events() {
    return eventStream;
  }

  /** 事件桥接 trace 适配：解释器埋点 → legacy 语义的 thought/action/observe 回调。 */
  private KernelTraceListener traceBridge() {
    return new KernelTraceListener() {
      @Override
      public void onDecision(GraphNode node, Decision d) {
        if (d.kind() == DecisionKind.ACT) {
          for (ToolCallReq call : d.toolCalls()) {
            eventStream.fireAction(call.name(), call.arguments());
          }
        } else {
          Object answer = d.artifacts().get("answer");
          eventStream.fireThought(
              answer != null ? answer.toString() : node.id() + ":" + d.kind().name());
        }
      }

      @Override
      public void onEffect(GraphNode node, ToolCallReq call, EffectOutcome outcome) {
        String observation = outcome.observation() != null ? outcome.observation() : "";
        eventStream.fireObserve(
            observation, outcome.success() ? call.name() : "failure:" + call.name(), 0L);
      }
    };
  }

  // ========================================================================
  // 内部
  // ========================================================================

  /** 每会话装配一次标准骨架并运行（brain 持有会话内状态，如 actor 排队）。 */
  private SessionOutcome runSync(String sessionId, String input, String modelId, int maxSteps) {
    logger.info(
        "[GraphSessionKernel] session {} start (model={}, task={} chars)",
        sessionId,
        modelId,
        input.length());

    StandardSkeleton skeleton =
        new StandardSkeleton(
            input,
            new PlannerGoalBrain(planner),
            new LlmActorBrain(inferencer, modelId, actorSystemPrompt, toolSpecs()),
            arbitration,
            verifyPost,
            new ToolRegistryEffectGateway(toolRegistry),
            revisionBudget,
            taoEffectBudget,
            traceBridge());
    Map<String, Object> options = new LinkedHashMap<>();
    options.put(R0Interpreter.OPTION_MAX_STEPS, maxSteps);
    // 取消安全点（#174）：解释器在每个节点边界检查；展开帧（子图）内同样生效
    options.put(
        R0Interpreter.OPTION_CANCEL_CHECK, (java.util.function.BooleanSupplier) () -> cancelled);
    if (walStore != null) {
      // WAL/Checkpoint 接入（§3.2 ⑥）：安全点 sink（节流到 goal 边界/会话 terminal）
      options.put(
          R0Interpreter.OPTION_CHECKPOINT_SINK,
          (org.cland.alice.core.agent.kernel.graph.CheckpointSink)
              sp -> persistSafePoint(sessionId, sp));
      SafePoint resume = loadResume(sessionId);
      if (resume != null) {
        options.put(R0Interpreter.OPTION_RESUME, resume);
        logger.info(
            "[GraphSessionKernel] resume session={} from node={} steps={}",
            sessionId,
            resume.pos(),
            resume.steps());
      }
    }
    return skeleton.run(options);
  }

  /**
   * 可选注入 WAL/Checkpoint 存储（链式）。
   *
   * <p>不注入 ⇒ 图内核行为与现状完全一致（安全点 sink/恢复均不生效）。注入后：`execute` 对已有 Checkpoint 的 sessionId
   * 自动恢复（日志可观测）；安全点按 {@link #isCheckpointBoundary(String)} 节流落盘。
   */
  public GraphSessionKernel walStore(WalStore store) {
    this.walStore = store;
    return this;
  }

  /**
   * 显式恢复入口：以 sessionId 加载最新图内核 Checkpoint 并续跑（恢复 = 回放 + 重路由，§3.2 ⑥）。
   *
   * <p>无 Checkpoint 时按新会话执行（等价普通 {@link #execute}）。
   */
  public Future<SessionResult> resume(String sessionId) {
    Objects.requireNonNull(sessionId, "sessionId");
    return execute(new SessionRequest(sessionId, "", defaultModelId, Map.of()));
  }

  private SafePoint loadResume(String sessionId) {
    WalStore store = walStore;
    if (store == null) {
      return null;
    }
    return store
        .getLatestCheckpoint(sessionId)
        .flatMap(GraphCheckpointAdapter::fromCheckpoint)
        .orElse(null);
  }

  private void persistSafePoint(String sessionId, SafePoint safePoint) {
    WalStore store = walStore;
    if (store == null || safePoint == null || !isCheckpointBoundary(safePoint.pos())) {
      return;
    }
    store.saveCheckpoint(GraphCheckpointAdapter.toCheckpoint(sessionId, safePoint));
    logger.info(
        "[GraphSessionKernel] checkpoint saved session={} node={} steps={}",
        sessionId,
        safePoint.pos(),
        safePoint.steps());
  }

  /** 安全点落盘节流：仅 goal 边界（verifyPost gate）与会话 terminal。 */
  private static boolean isCheckpointBoundary(String pos) {
    return StandardSkeleton.N_VP.equals(pos) || StandardSkeleton.N_SESSION_END.equals(pos);
  }

  private int maxStepsOf(SessionRequest request) {
    Object legacy = request.options().get("maxIterations");
    Object modern = request.options().get("maxSteps");
    if (modern instanceof Number n && n.intValue() > 0) {
      return n.intValue();
    }
    if (legacy instanceof Number n && n.intValue() > 0) {
      // legacy maxIterations 语义 → 图遍历步数（P4：步数单一来源）
      return n.intValue() * 100;
    }
    return DEFAULT_MAX_STEPS;
  }

  private List<ToolSpec> toolSpecs() {
    List<ToolSpec> specs = new ArrayList<>();
    for (var meta : toolRegistry.allTools()) {
      specs.add(new ToolSpec(meta.name(), meta.description(), meta.inputSchema()));
    }
    return specs;
  }

  private SessionResult toResult(String sessionId, SessionOutcome outcome) {
    Map<String, Object> meta = new LinkedHashMap<>();
    meta.put("steps", outcome.steps());
    if (outcome.status() == SessionOutcome.Status.FAILED) {
      meta.put("error", outcome.message());
    }
    SessionStatus status =
        switch (outcome.status()) {
          case FINISHED -> SessionStatus.FINISHED;
          case CANCELLED -> SessionStatus.CANCELLED;
          case FAILED -> SessionStatus.FAILED;
        };
    return new SessionResult(status, sessionId, outcome.answer(), outcome.steps(), meta);
  }

  /** 事件流实现：线程安全订阅 + 内部触发（由 trace 桥调用）。 */
  private static final class KernelEventStream implements EventStream {
    private final java.util.List<Listener> listeners = new CopyOnWriteArrayList<>();

    @Override
    public void subscribe(Listener listener) {
      if (listener != null) {
        listeners.add(listener);
      }
    }

    @Override
    public void unsubscribe(Listener listener) {
      if (listener != null) {
        listeners.remove(listener);
      }
    }

    void fireThought(String text) {
      for (Listener l : listeners) {
        try {
          l.onThought(text);
        } catch (Exception ignored) {
          // 监听器异常不影响内核执行
        }
      }
    }

    void fireAction(String tool, java.util.Map<String, Object> params) {
      for (Listener l : listeners) {
        try {
          l.onAction(tool, params);
        } catch (Exception ignored) {
        }
      }
    }

    void fireObserve(String rawData, String summary, long elapsedMs) {
      for (Listener l : listeners) {
        try {
          l.onObserve(rawData, summary, elapsedMs);
        } catch (Exception ignored) {
        }
      }
    }
  }
}
