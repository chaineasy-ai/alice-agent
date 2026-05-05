package org.cland.alice.facade.cmd;

import org.cland.alice.core.agent.Agent;
import org.cland.alice.core.agent.AgentConfig;
import org.cland.alice.core.agent.AgentContext;
import org.cland.alice.core.agent.result.StepResult;
import org.cland.alice.facade.cmd.config.RunConfig;
import org.cland.alice.facade.cmd.render.OutputRenderer;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 执行协调器，驱动 Agent 核心完成一次 CLI 任务。
 * <p>
 * 对应设计文档中 {@code ExecutionCoordinator} 组件的职责：
 * <ul>
 *   <li>接收 {@link RunConfig}</li>
 *   <li>初始化 Agent 核心</li>
 *   <li>驱动 PPAO 循环，通过 {@link OutputRenderer} 实时输出</li>
 *   <li>处理超时和退出码</li>
 * </ul>
 */
public final class ExecutionCoordinator {

    private static final System.Logger logger = System.getLogger(ExecutionCoordinator.class.getName());

    private final RunConfig config;
    private final OutputRenderer renderer;

    /**
     * 创建执行协调器。
     *
     * @param config   运行配置
     * @param renderer 输出渲染器
     */
    public ExecutionCoordinator(RunConfig config, OutputRenderer renderer) {
        this.config = config;
        this.renderer = renderer;
    }

    /**
     * 执行任务（同步阻塞）。
     * <p>
     * 流程：
     * <ol>
     *   <li>根据 RunConfig 构建 AgentConfig</li>
     *   <li>创建 Agent 实例</li>
     *   <li>提交任务并等待完成</li>
     *   <li>输出最终结果</li>
     * </ol>
     *
     * @return 退出码（0 成功，1 失败）
     */
    public int execute() {
        logger.log(System.Logger.Level.INFO, "Starting task: {0}", config.task());

        try {
            // 1. 构建 AgentConfig
            AgentConfig agentConfig = AgentConfig.builder()
                .defaultModelId(config.model())
                .debug(config.verbose())
                .build();

            // 2. 创建 Agent
            Agent agent = new Agent(agentConfig);
            logger.log(System.Logger.Level.DEBUG, "Agent created: {0}", agent.agentId());

            // 3. 构建上下文
            AgentContext context = new AgentContext();
            context.put("prompt", config.task());
            context.put("model", config.model());

            // 4. 检查 stdin 是否有管道输入
            String stdinInput = readStdin();
            if (stdinInput != null && !stdinInput.isBlank()) {
                context.put("stdin", stdinInput);
                logger.log(System.Logger.Level.DEBUG, "Stdin input captured: {0} chars", stdinInput.length());
            }

            // 5. 同步执行
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<AgentContext> resultRef = new AtomicReference<>();
            AtomicReference<Throwable> errorRef = new AtomicReference<>();

            long timeoutMs = config.timeoutSeconds() * 1000;

            agent.askAsync(config.task())
                .onSuccess(ctx -> {
                    resultRef.set(ctx);
                    latch.countDown();
                })
                .onFailure(err -> {
                    errorRef.set(err);
                    latch.countDown();
                });

            boolean completed = latch.await(timeoutMs, TimeUnit.MILLISECONDS);

            if (!completed) {
                renderer.renderError(
                    "Task timed out after " + config.timeoutSeconds() + "s",
                    config);
                agent.close();
                return 1;
            }

            if (errorRef.get() != null) {
                renderer.renderError(
                    "Task failed: " + errorRef.get().getMessage(),
                    config);
                if (config.verbose()) {
                    errorRef.get().printStackTrace(System.err);
                }
                agent.close();
                return 1;
            }

            // 6. 获取结果并输出
            AgentContext resultCtx = resultRef.get();
            if (resultCtx != null) {
                Object resultObj = resultCtx.get("result");
                String result = resultObj != null ? resultObj.toString() : "No result produced.";

                // 实时渲染中间步骤 — 从上下文的思考链中提取
                String thoughtChain = resultCtx.thoughtChain();
                if (config.verbose() && !thoughtChain.isBlank()) {
                    // 思考链通过迭代步骤渲染
                    renderStepResults(resultCtx);
                }

                renderer.renderFinal(result, config);
            } else {
                renderer.renderFinal("Task completed but no result context.", config);
            }

            agent.close();
            return 0;

        } catch (Exception e) {
            logger.log(System.Logger.Level.ERROR, "Execution failed", e);
            renderer.renderError("Unexpected error: " + e.getMessage(), config);
            if (config.verbose()) {
                e.printStackTrace(System.err);
            }
            return 1;
        }
    }

    // ========================================================================
    // 辅助
    // ========================================================================

    /**
     * 从上下文中解析中间步骤结果并渲染。
     * 当前实现为占位：真实场景中需要 AgentExecutor 通过 callback 流式发布 StepResult。
     */
    private void renderStepResults(AgentContext context) {
        String thoughtChain = context.thoughtChain();
        if (thoughtChain == null || thoughtChain.isBlank()) {
            return;
        }

        String[] steps = thoughtChain.split("\n---\n");
        for (int i = 0; i < steps.length; i++) {
            String step = steps[i].trim();
            if (!step.isBlank()) {
                renderer.render(
                    StepResult.finish(step),
                    config
                );
            }
        }
    }

    /**
     * 尝试从 stdin 读取管道数据（非交互式）。
     * 仅在 System.in 有数据可用时读取。
     */
    private String readStdin() {
        try {
            if (System.in.available() > 0) {
                byte[] buffer = new byte[System.in.available()];
                int bytesRead = System.in.read(buffer);
                if (bytesRead > 0) {
                    return new String(buffer, 0, bytesRead, java.nio.charset.StandardCharsets.UTF_8).trim();
                }
            }
        } catch (Exception e) {
            logger.log(System.Logger.Level.DEBUG, "No stdin data available");
        }
        return null;
    }
}
