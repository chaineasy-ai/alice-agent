package org.cland.alice.facade.cmd.render;

import org.cland.alice.core.agent.result.StepResult;
import org.cland.alice.facade.cmd.config.RunConfig;

/**
 * 输出渲染器接口。
 * <p>
 * 对应设计文档中 {@code OutputRenderer} 接口，负责将 Agent 执行过程中的
 * 步骤结果和最终结论格式化为文本/JSON 输出到标准输出流。
 *
 * <p>
 * 两个实现：
 * <ul>
 *   <li>{@link TextOutputRenderer} — 增强文本格式（默认）</li>
 *   <li>{@link JsonOutputRenderer} — JSON 结构化输出（--json）</li>
 * </ul>
 */
public interface OutputRenderer {

    /**
     * 渲染中间步骤结果（Thought / Action / Observation）。
     *
     * @param stepResult PPAO 循环中的步骤结果
     * @param config     运行配置（用于 verbose 等开关判定）
     */
    void render(StepResult stepResult, RunConfig config);

    /**
     * 渲染最终总结。
     *
     * @param summary 最终结论文本
     * @param config  运行配置
     */
    void renderFinal(String summary, RunConfig config);

    /**
     * 渲染错误信息。
     *
     * @param errorMessage 错误描述
     * @param config       运行配置
     */
    void renderError(String errorMessage, RunConfig config);

    // ========================================================================
    // 工厂方法
    // ========================================================================

    /**
     * 根据配置创建适当的渲染器实例。
     *
     * @param config 运行配置
     * @return 文本或 JSON 渲染器
     */
    static OutputRenderer create(RunConfig config) {
        if (config.jsonOutput()) {
            return new JsonOutputRenderer();
        }
        return new TextOutputRenderer();
    }
}
