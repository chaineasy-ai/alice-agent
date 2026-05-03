package org.cland.alice.core.agent;

import org.cland.alice.model.Call;
import org.cland.alice.model.ModelProvider;

/**
 * Agent 核心类，代表一个 AI Agent 实例。
 * 通过 ModelProvider 与底层模型交互。
 */
public class Agent {

    private static final System.Logger logger = System.getLogger(Agent.class.getName());

    private final String agentId;

    public Agent() {
        this.agentId = java.util.UUID.randomUUID().toString().substring(0, 8);
    }

    public Agent(String agentId) {
        this.agentId = agentId;
    }

    public String agentId() {
        return agentId;
    }

    /**
     * 使用默认上下文运行 Agent。
     */
    public void run() {
        run(new AgentContext());
    }

    /**
     * 使用指定上下文运行 Agent。
     */
    public void run(AgentContext context) {
        String prompt = context.containsKey("prompt")
            ? context.get("prompt").toString()
            : "Hello!";

        String modelId = context.containsKey("model")
            ? context.get("model").toString()
            : "gpt-4o-mini";

        logger.log(System.Logger.Level.INFO, "Agent {0} running with model {1}", agentId, modelId);

        ModelProvider provider = ModelProvider.getInstance();
        Call result = provider.dispatch(modelId, prompt);

        logger.log(System.Logger.Level.INFO, "Agent {0} completed: status={1}, latency={2}ms",
            agentId, result.status(), result.metrics().latencyMs());

        context.put("result", result.result() != null ? result.result().content() : null);
        context.put("status", result.status());
        context.put("traceId", result.traceId());
    }

    /**
     * 向 Agent 发送 prompt，返回响应内容。
     * <p>
     * 对应设计文档中 Agent 的 ask() 方法。
     *
     * @param prompt 用户输入的提示词
     * @return 模型返回的响应文本
     */
    public String ask(String prompt) {
        return ask(prompt, "gpt-4o-mini");
    }

    /**
     * 向 Agent 发送 prompt，指定模型。
     *
     * @param prompt  用户输入的提示词
     * @param modelId 目标模型 ID
     * @return 模型返回的响应文本
     */
    public String ask(String prompt, String modelId) {
        logger.log(System.Logger.Level.INFO, "Agent {0} ask model={1}", agentId, modelId);

        ModelProvider provider = ModelProvider.getInstance();
        Call result = provider.dispatch(modelId, prompt);

        logger.log(System.Logger.Level.INFO, "Agent {0} response status={1}, tokens={2}",
            agentId, result.status(),
            result.metrics().tokenUsage() != null ? result.metrics().tokenUsage().totalTokens() : "N/A");

        if (result.result() == null) {
            throw new RuntimeException("Agent call failed: " + result.status());
        }
        return result.result().content();
    }
}
