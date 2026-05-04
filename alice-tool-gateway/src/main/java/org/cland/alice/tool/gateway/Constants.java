package org.cland.alice.tool.gateway;

/**
 * alice-tool-gateway 模块的全局常量配置。
 */
public final class Constants {

    private Constants() {}

    /** 工具配置文件路径（支持外部化） */
    public static final String TOOL_CONFIG_PATH = "";

    /** 默认工具执行超时时间（毫秒） */
    public static final long DEFAULT_EXECUTION_TIMEOUT_MS = 30_000;

    /** 沙箱容器镜像默认仓库地址 */
    public static final String DEFAULT_SANDBOX_IMAGE_REPO = "docker.io/alice/sandbox";

    /** 工具调用请求中参数 key 的最大长度 */
    public static final int MAX_PARAM_KEY_LENGTH = 128;

    /** 工具调用请求中参数 value 的最大长度 */
    public static final int MAX_PARAM_VALUE_LENGTH = 1_048_576; // 1MB
}
