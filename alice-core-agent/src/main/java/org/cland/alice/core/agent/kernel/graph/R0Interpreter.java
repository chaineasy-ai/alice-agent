package org.cland.alice.core.agent.kernel.graph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * R0 唯一遍历器（§5.2.3）— 图元模型的机械推导。
 *
 * <p>内核全部执行代码 = 本解释器：节点类型分派、展开帧、预算检查、两个写点。 业务智能全部落在"图怎么搭、节点上挂谁"（由 L2 Agent 装配）。位置 = 节点 +
 * 展开帧栈；终止/修订/预算全在图注解与 exit port。
 *
 * <p><b>不变式（防止越层）</b>：内部 terminal 不能直接到达外层——唯一通道是 exit port 绑定表； "会话结束"只能是会话级 terminal
 * 从最外层到达（{@link SessionGraph#SESSION_END}）。
 */
public final class R0Interpreter {

  private static final Logger logger = LoggerFactory.getLogger(R0Interpreter.class);

  /** gate/effect 的放行边端口。 */
  public static final String PORT_PASS = "pass";

  /** gate/effect 的 guard 边端口（block / 注解耗尽）。 */
  public static final String PORT_GUARD = "guard";

  /** 遍历步数预算键（run options）：步数 = 图遍历步数（单一来源，修 P4 双计数）。 */
  public static final String OPTION_MAX_STEPS = "maxSteps";

  /**
   * 取消检查键（run options，值为 {@link java.util.function.BooleanSupplier}）：
   *
   * <p>在每个节点边界（decision / effect / gate / observe / composite / terminal）检查；命中即停止遍历并收敛为 {@link
   * SessionOutcome.Status#CANCELLED}。展开帧（子图）内部同样生效——取消信号随遍历全局传播（嵌套传播骨架）。
   */
  public static final String OPTION_CANCEL_CHECK = "cancelCheck";

  /** 默认遍历步数预算。 */
  public static final int DEFAULT_MAX_STEPS = 10_000;

  /** 复合展开帧：内部图 + exit port 绑定表。 */
  private record Frame(SessionGraph graph, Map<String, String> bindings) {}

  private final Map<String, DecisionSource> decisions = new HashMap<>();
  private final Map<String, EffectGateway> effects = new HashMap<>();
  private final Map<String, GatePolicy> gates = new HashMap<>();

  /** 可选执行 trace 监听（埋点不影响执行）。 */
  private KernelTraceListener trace;

  // ========== 装配 ==========

  public R0Interpreter decision(String nodeId, DecisionSource source) {
    decisions.put(nodeId, Objects.requireNonNull(source, "source"));
    return this;
  }

  public R0Interpreter effect(String nodeId, EffectGateway gateway) {
    effects.put(nodeId, Objects.requireNonNull(gateway, "gateway"));
    return this;
  }

  public R0Interpreter gate(String nodeId, GatePolicy policy) {
    gates.put(nodeId, Objects.requireNonNull(policy, "policy"));
    return this;
  }

  /** 装配执行 trace 监听（thought/action/observe 语义事件形态）。 */
  public R0Interpreter trace(KernelTraceListener listener) {
    this.trace = listener;
    return this;
  }

  // ========== 执行 ==========

  /** 运行一次会话图遍历。 */
  public SessionOutcome run(SessionGraph graph) {
    return run(graph, Map.of());
  }

  /** 运行一次会话图遍历（options：maxSteps 等预算注解）。 */
  public SessionOutcome run(SessionGraph graph, Map<String, Object> options) {
    Objects.requireNonNull(graph, "graph");
    Map<String, Object> opts = options != null ? options : Map.of();

    // 准备：全树 id 唯一性 + terminal 出口完整 + 策略挂点存在（一次性校验）
    Assembly assembly = prepare(graph);

    Ledger ledger = new Ledger();
    int maxSteps =
        opts.get(OPTION_MAX_STEPS) instanceof Number n && n.intValue() > 0
            ? n.intValue()
            : DEFAULT_MAX_STEPS;

    String pos = graph.startId();
    String lastObservation = "";
    ToolCallReq pendingEffect = null;
    Map<String, Integer> effectRuns = new HashMap<>();
    List<Frame> frames = new ArrayList<>();
    frames.add(new Frame(graph, graph.terminalExits()));

    int steps = 0;
    java.util.function.BooleanSupplier cancelCheck = cancelCheckOf(opts);
    while (true) {
      steps++;
      // 取消安全点（#174）：节点边界统一检查；命中即停止遍历、不执行当前节点（不产生副作用）
      if (cancelCheck != null && cancelCheck.getAsBoolean()) {
        ledger.appendTrace("cancel:safe-point");
        return new SessionOutcome(
            SessionOutcome.Status.CANCELLED,
            "session cancelled at traversal safe point",
            steps,
            ledger.trace(),
            ledger.artifacts());
      }
      if (steps > maxSteps) {
        return new SessionOutcome(
            SessionOutcome.Status.FAILED,
            "step budget exhausted after " + maxSteps + " traversal steps",
            steps,
            ledger.trace(),
            ledger.artifacts());
      }

      GraphNode node = assembly.nodes().get(pos);
      SessionGraph owner = assembly.owners().get(pos);
      if (node == null) {
        return fail(ledger, steps, "position points to unknown node [" + pos + "]");
      }
      ledger.appendTrace("step" + steps + ":" + node.id() + "(" + node.kind() + ")");

      switch (node.kind()) {
        case DECISION -> {
          DecisionSource source = decisions.get(node.id());
          if (source == null) {
            return fail(ledger, steps, "no DecisionSource for node [" + node.id() + "]");
          }
          Decision d = source.decide(node, ledger, lastObservation);
          fire(n -> n.onDecision(node, d));
          // 写点 ①：decision 结果落账（goal 绑定 / 仲裁结论 / route/判据/预算 / 会话产物）
          if (d.hasStateWrite()) {
            if (!d.bindings().isEmpty()) {
              ledger.bindGoals(d.bindings());
            }
            if (!d.meta().isEmpty()) {
              ledger.recordArbitration(d.meta());
            }
            if (!d.artifacts().isEmpty()) {
              d.artifacts().forEach(ledger::recordArtifact);
            }
          }
          if (d.kind() == DecisionKind.ACT && !d.toolCalls().isEmpty()) {
            pendingEffect = d.toolCalls().get(0);
          }
          String next = routeFrom(owner, node, d.route());
          if (next == null) {
            return fail(
                ledger,
                steps,
                "no route edge from [" + node.id() + "] for route [" + d.route() + "]");
          }
          pos = next;
        }
        case EFFECT -> {
          int budget = budgetOf(node);
          int runs = effectRuns.getOrDefault(node.id(), 0);
          if (budget >= 0 && runs >= budget) {
            // R3：注解耗尽 → 推到 guard 边（若图未声明 guard 边则结构性失败）
            String guard = guardTarget(owner, node.id());
            if (guard == null) {
              return fail(
                  ledger,
                  steps,
                  "effect budget exhausted at [" + node.id() + "] without guard edge");
            }
            pos = guard;
            break;
          }
          EffectGateway gateway = effects.get(node.id());
          if (gateway == null) {
            return fail(ledger, steps, "no EffectGateway for node [" + node.id() + "]");
          }
          ToolCallReq call = pendingEffect != null ? pendingEffect : toolFromAttrs(node);
          EffectOutcome outcome = gateway.invoke(call);
          fire(n -> n.onEffect(node, call, outcome));
          pendingEffect = null;
          lastObservation = outcome.observation() != null ? outcome.observation() : "";
          effectRuns.merge(node.id(), 1, Integer::sum);
          ledger.appendTrace("effect:" + call.name() + " ok=" + outcome.success());
          String next = mainTarget(owner, node.id());
          if (next == null) {
            return fail(ledger, steps, "effect node [" + node.id() + "] has no main out edge");
          }
          pos = next;
        }
        case GATE -> {
          GatePolicy policy = gates.get(node.id());
          if (policy == null) {
            return fail(ledger, steps, "no GatePolicy for node [" + node.id() + "]");
          }
          GateResult verdict = policy.verify(node, ledger, lastObservation);
          fire(n -> n.onGate(node, verdict));
          String port = verdict.pass() ? PORT_PASS : verdict.guardPort();
          String target = portTarget(owner, node.id(), port);
          if (target == null) {
            return fail(ledger, steps, "gate node [" + node.id() + "] has no [" + port + "] edge");
          }
          ledger.appendTrace(
              "gate:" + node.id() + (verdict.pass() ? "=PASS" : "=BLOCK(" + port + ")"));
          pos = target;
        }
        case OBSERVE -> {
          String observation = lastObservation;
          fire(n -> n.onObserve(node, observation));
          String next = mainTarget(owner, node.id());
          if (next == null) {
            return fail(ledger, steps, "observe node [" + node.id() + "] has no out edge");
          }
          // 上下文重建（修 P8）由装配的策略挂点内容决定；解释器此处只推进游标
          pos = next;
        }
        case COMPOSITE -> {
          CompositeSpec spec = node.composite();
          if (spec == null) {
            return fail(ledger, steps, "composite node [" + node.id() + "] missing spec");
          }
          // 展开帧入栈：内层图 + exit port 绑定表
          frames.add(new Frame(spec.inner(), spec.exitBindings()));
          pos = spec.inner().startId();
        }
        case TERMINAL -> {
          // 唯一出口：当前图所在帧的绑定表；SESSION_END = 会话级终止（唯一会话出口）
          Frame frame = frameOf(frames, owner);
          if (frame == null) {
            return fail(ledger, steps, "terminal [" + node.id() + "] has no expansion frame");
          }
          String target = frame.bindings().get(node.id());
          if (target == null) {
            return fail(ledger, steps, "terminal [" + node.id() + "] has no exit port binding");
          }
          if (SessionGraph.SESSION_END.equals(target)) {
            return new SessionOutcome(
                SessionOutcome.Status.FINISHED,
                "session ended",
                steps,
                ledger.trace(),
                ledger.artifacts());
          }
          pos = target;
          // 离开当前图：弹出其帧，直到栈顶对应出口目标的归属图
          while (!frames.isEmpty()
              && !frames.get(frames.size() - 1).graph().equals(assembly.owners().get(pos))) {
            frames.remove(frames.size() - 1);
          }
        }
      }
    }
  }

  // ========== 内部 ==========

  /** trace 触发：监听器异常不影响执行。 */
  private void fire(java.util.function.Consumer<KernelTraceListener> callback) {
    KernelTraceListener t = trace;
    if (t == null) {
      return;
    }
    try {
      callback.accept(t);
    } catch (Exception e) {
      logger.warn("[R0] trace listener threw exception", e);
    }
  }

  /** 取消检查选项解析：非 BooleanSupplier 视为未提供（向后兼容）。 */
  private static java.util.function.BooleanSupplier cancelCheckOf(Map<String, Object> opts) {
    Object v = opts.get(OPTION_CANCEL_CHECK);
    return v instanceof java.util.function.BooleanSupplier b ? b : null;
  }

  private SessionOutcome fail(Ledger ledger, int steps, String message) {
    return new SessionOutcome(
        SessionOutcome.Status.FAILED, message, steps, ledger.trace(), ledger.artifacts());
  }

  /** 效果预算注解：无注解返回 -1（不限）。 */
  private int budgetOf(GraphNode node) {
    Object v = node.attr(GraphNode.ATTR_MAX_EFFECTS, null);
    return v instanceof Number n ? n.intValue() : -1;
  }

  private ToolCallReq toolFromAttrs(GraphNode node) {
    String tool = (String) node.attr("tool", null);
    @SuppressWarnings("unchecked")
    Map<String, Object> params = (Map<String, Object>) node.attr("params", Map.of());
    return ToolCallReq.of(tool != null ? tool : "<attr-tool-missing>", params);
  }

  /** 决策路由：优先 route 端口，其次唯一出边。 */
  private String routeFrom(SessionGraph owner, GraphNode node, String route) {
    if (route != null) {
      String t = portTarget(owner, node.id(), route);
      if (t != null) {
        return t;
      }
    }
    List<GraphEdge> outs = owner.outgoing(node.id());
    return outs.size() == 1 ? outs.get(0).to() : null;
  }

  private String portTarget(SessionGraph owner, String nodeId, String port) {
    for (GraphEdge e : owner.outgoing(nodeId)) {
      if (port == null ? e.port() == null : port.equals(e.port())) {
        return e.to();
      }
    }
    return null;
  }

  /** 无条件主出边（port 为 null）；无则取唯一非 guard 出边。 */
  private String mainTarget(SessionGraph owner, String nodeId) {
    String plain = portTarget(owner, nodeId, null);
    if (plain != null) {
      return plain;
    }
    for (GraphEdge e : owner.outgoing(nodeId)) {
      if (!PORT_GUARD.equals(e.port())) {
        return e.to();
      }
    }
    return null;
  }

  private String guardTarget(SessionGraph owner, String nodeId) {
    return portTarget(owner, nodeId, PORT_GUARD);
  }

  private Frame frameOf(List<Frame> frames, SessionGraph owner) {
    for (int i = frames.size() - 1; i >= 0; i--) {
      if (frames.get(i).graph() == owner) {
        return frames.get(i);
      }
    }
    return null;
  }

  // ========== 装配校验（prepare） ==========

  /** 装配结果：全树节点 → 归属图。 */
  private record Assembly(Map<String, GraphNode> nodes, Map<String, SessionGraph> owners) {}

  private Assembly prepare(SessionGraph root) {
    Map<String, GraphNode> byId = new HashMap<>();
    Map<String, SessionGraph> ownerOf = new HashMap<>();
    List<CompositeSpec> composites = new ArrayList<>();

    collect(root, null, byId, ownerOf, composites);

    // 校验 1：terminal 出口绑定完整（顶层经 terminalExits；内层经其直接 CompositeSpec 绑定表）
    for (CompositeSpec spec : composites) {
      for (Map.Entry<String, String> e : spec.exitBindings().entrySet()) {
        if (!spec.inner().nodes().containsKey(e.getKey())) {
          throw new IllegalArgumentException(
              "composite exit binding references unknown terminal [" + e.getKey() + "]");
        }
        if (spec.inner().node(e.getKey()).kind() != NodeKind.TERMINAL) {
          throw new IllegalArgumentException(
              "composite exit binding [" + e.getKey() + "] is not a TERMINAL node");
        }
        resolveTarget(e.getValue(), byId);
      }
    }
    for (Map.Entry<String, String> e : root.terminalExits().entrySet()) {
      if (root.node(e.getKey()).kind() != NodeKind.TERMINAL) {
        throw new IllegalArgumentException(
            "root terminal exit [" + e.getKey() + "] is not a TERMINAL node");
      }
      resolveTarget(e.getValue(), byId);
    }
    return new Assembly(Map.copyOf(byId), Map.copyOf(ownerOf));
  }

  private void resolveTarget(String target, Map<String, GraphNode> byId) {
    if (!SessionGraph.SESSION_END.equals(target) && !byId.containsKey(target)) {
      throw new IllegalArgumentException("exit port binds to unknown node [" + target + "]");
    }
  }

  private void collect(
      SessionGraph graph,
      SessionGraph unused,
      Map<String, GraphNode> byId,
      Map<String, SessionGraph> ownerOf,
      List<CompositeSpec> composites) {
    for (GraphNode node : graph.nodes().values()) {
      if (byId.containsKey(node.id())) {
        throw new IllegalArgumentException(
            "duplicate node id [" + node.id() + "] across nested graphs");
      }
      byId.put(node.id(), node);
      ownerOf.put(node.id(), graph);
      if (node.isComposite()) {
        CompositeSpec spec = node.composite();
        composites.add(spec);
        collect(spec.inner(), graph, byId, ownerOf, composites);
      }
    }
  }
}
