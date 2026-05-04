package org.cland.alice.core.agent;

import io.vertx.core.Vertx;
import org.cland.alice.core.agent.executor.AgentExecutor;
import org.cland.alice.core.agent.result.StepResult;
import org.cland.alice.model.Call;
import org.cland.alice.model.ModelProvider;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Agent 核心类，代表一个 AI Agent 实例。
 * <p>
 * 基于 PPAO (Perceive-Plan-Act-Observe-Verify) 核心循环。
 * 通过 ModelProvider 与底层模型交互，通过 AgentExecutor 驱动响应式执行循环。
 * <p>
 * 使用示例：
 * <pre>
 *   Agent agent = new Agent();
 *   String result = agent.ask("What is the capital of France?");
 *   System.out.println(result); // "Paris"
 * </pre>
 */
public class Agent {

    private static final System.Logger logger = System.getLogger(Agent.class.getName());

    private final String agentId;
    private final AgentCore agentCore;
    private final AgentConfig config;
    private final Vertx vertx;
    private final AgentExecutor executor;

    // ========== 构造 ==========

    public Agent() {
        this(null, AgentConfig.defaults());
    }

    public Agent(String agentId) {
        this(agentId, AgentConfig.defaults());
    }

    public Agent(AgentConfig config) {
        this(null, config);
    }

    private Agent(String agentId, AgentConfig config) {
        this.agentId = agentId != null
            ? agentId
            : java.util.UUID.randomUUID().toString().substring(0, 8);
        this.config = config;
        this.agentCore = new AgentCore(this.agentId, config);
        this.vertx = Vertx.vertx();
        this.executor = new AgentExecutor(vertx, agentCore);
    }

    // ========== 属性 ==========

    public String agentId()                  { return agentId; }
    public AgentCore agentCore()             { return agentCore; }
    public AgentConfig config()              { return config; }

    // ========== 同步 API ==========

    /**
     * 使用默认上下文运行 Agent（同步阻塞）。
     */
    public void run() {
        run(new AgentContext());
    }

    /**
     * 使用指定上下文运行 Agent（同步阻塞）。
     * <p>
     * 内部执行 PPAO 循环，等待结果返回。
     */
    public void run(AgentContext context) {
        String prompt = context.containsKey("prompt")
            ? context.get("prompt").toString()
            : "Hello!";

        String modelId = context.containsKey("model")
            ? context.get("model").toString()
            : config.defaultModelId();

        logger.log(System.Logger.Level.INFO, "Agent {0} running with model {1}", agentId, modelId);

        // 同步执行 PPAO 循环
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<AgentContext> resultRef = new AtomicReference<>();

        executor.execute(prompt, context)
            .onSuccess(ctx -> {
                resultRef.set(ctx);
                latch.countDown();
            })
            .onFailure(err -> {
                logger.log(System.Logger.Level.ERROR, "Agent {0} PPAO loop failed", agentId, err);
                context.put("error", err.getMessage());
                context.put("status", "FATAL_ERROR");
                latch.countDown();
            });

        try {
            latch.await(config.actionTimeoutMs() * 2, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            context.put("error", "Interrupted: " + e.getMessage());
        }

        AgentContext resultCtx = resultRef.get();
        if (resultCtx != null) {
            context.putAll(resultCtx.asMap());
        }

        logger.log(System.Logger.Level.INFO, "Agent {0} completed");
    }

    /**
     * 向 Agent 发送 prompt，返回响应内容（同步阻塞）。
     * <p>
     * 对应设计文档中 Agent 的 ask() 方法。
     *
     * @param prompt 用户输入的提示词
     * @return 模型返回的响应文本
     */
    public String ask(String prompt) {
        return ask(prompt, config.defaultModelId());
    }

    /**
     * 向 Agent 发送 prompt，指定模型（同步阻塞）。
     * <p>
     * 使用 PPAO 循环执行，如果循环产生 Finish 结果则返回 answer，
     * 否则回退到直接调用 ModelProvider。
     *
     * @param prompt  用户输入的提示词
     * @param modelId 目标模型 ID
     * @return 模型返回的响应文本
     */
    public String ask(String prompt, String modelId) {
        logger.log(System.Logger.Level.INFO, "Agent {0} ask model={1}", agentId, modelId);

        AgentContext context = new AgentContext(config.maxIterations());
        context.put("prompt", prompt);
        context.put("model", modelId);

        // 同步执行 PPAO 循环
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> resultRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        executor.execute(prompt, context)
            .onSuccess(ctx -> {
                String result = ctx.containsKey("result")
                    ? ctx.get("result").toString()
                    : null;
                if (result == null) {
                    // 回退：直接调用 LLM
                    result = callLlmDirect(prompt, modelId);
                }
                resultRef.set(result);
                latch.countDown();
            })
            .onFailure(err -> {
                errorRef.set(err);
                latch.countDown();
            });

        try {
            latch.await(config.actionTimeoutMs() * 2, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Agent ask interrupted", e);
        }

        if (errorRef.get() != null) {
            throw new RuntimeException("Agent ask failed", errorRef.get());
        }

        String result = resultRef.get();
        if (result == null) {
            throw new RuntimeException("Agent ask returned null result");
        }

        logger.log(System.Logger.Level.INFO, "Agent {0} response length={1}", agentId, result.length());
        return result;
    }

    // ========== 异步 API ==========

    /**
     * 异步执行 PPAO 循环。
     *
     * @param prompt 用户输入
     * @return 异步结果（io.vertx.core.Future）
     */
    public io.vertx.core.Future<AgentContext> askAsync(String prompt) {
        AgentContext context = new AgentContext(config.maxIterations());
        context.put("prompt", prompt);
        return executor.execute(prompt, context);
    }

    /**
     * 获取底层的 Vertx 实例，便于调用方集成。
     */
    public Vertx vertx() {
        return vertx;
    }

    /**
     * 关闭 Agent 释放资源。
     */
    public void close() {
        vertx.close();
    }

    // ========== 辅助 ==========

    /** 直接调用 LLM（回退逻辑） */
    private String callLlmDirect(String prompt, String modelId) {
        logger.log(System.Logger.Level.DEBUG, "Falling back to direct LLM call: model={0}", modelId);
        ModelProvider provider = ModelProvider.getInstance();
        Call result = provider.dispatch(modelId, prompt);

        if (result.result() == null) {
            throw new RuntimeException("Agent call failed: " + result.status());
        }

        logger.log(System.Logger.Level.INFO, "Agent {0} direct LLM response status={1}, tokens={2}",
            agentId, result.status(),
            result.metrics().tokenUsage() != null ? result.metrics().tokenUsage().totalTokens() : "N/A");

        return result.result().content();
    }
}
