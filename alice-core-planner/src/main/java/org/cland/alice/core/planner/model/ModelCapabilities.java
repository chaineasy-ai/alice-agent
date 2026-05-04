package org.cland.alice.core.planner.model;

/**
 * 模型能力抽象，对应设计文档中的 {@code ModelCapabilities}。
 * <p>
 * 定义模型的能力标签，用于策略路由选择合适模型。
 */
public enum ModelCapabilities {

    /** 无特殊能力 */
    NONE(false, false),
    /** 支持函数调用 */
    FUNCTION_CALL(true, false),
    /** 支持流式输出 */
    STREAMING(false, true),
    /** 全功能 */
    ALL(true, true);

    private final boolean supportsFunctionCall;
    private final boolean supportsStreaming;

    ModelCapabilities(boolean supportsFunctionCall, boolean supportsStreaming) {
        this.supportsFunctionCall = supportsFunctionCall;
        this.supportsStreaming = supportsStreaming;
    }

    public boolean supportsFunctionCall() { return supportsFunctionCall; }
    public boolean supportsStreaming()    { return supportsStreaming; }
}
