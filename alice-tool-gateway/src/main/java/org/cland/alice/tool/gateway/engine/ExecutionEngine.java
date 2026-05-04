package org.cland.alice.tool.gateway.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.cland.alice.tool.gateway.ToolRegistry;
import org.cland.alice.tool.gateway.annotation.RiskLevel;
import org.cland.alice.tool.gateway.metadata.ToolMetadata;
import org.cland.alice.tool.gateway.sandbox.DirectSandboxProvider;
import org.cland.alice.tool.gateway.sandbox.PolicySandboxProvider;
import org.cland.alice.tool.gateway.sandbox.SandboxProvider;

import java.util.Map;
import java.util.concurrent.*;

/**
 * 工具执行引擎 — 核心调度器。
 * <p>
 * 对应设计文档类图中 {@code ExecutionEngine}，以及 §3 沙箱执行时序图。
 * <p>
 * 职责：
 * <ol>
 *   <li>接收工具名称和参数</li>
 *   <li>通过 {@link ToolRegistry} 查找工具元数据</li>
 *   <li>根据 {@link RiskLevel} 选择沙箱策略</li>
 *   <li>执行工具方法，捕获返回值或异常</li>
 *   <li>包装结果为 {@link ToolResult} 返回</li>
 *   <li>支持超时控制</li>
 * </ol>
 * <p>
 * <b>设计说明</b>：本模块不依赖 alice-core-agent 的 {@code Action/Observation} 类型，
 * 以保持工具网关的纯净性。上层（AgentCore）负责将 {@link ToolResult} 桥接为 {@code Observation}。
 */
public class ExecutionEngine {

    private final ToolRegistry registry;
    private final ObjectMapper mapper;
    private final Map<RiskLevel, SandboxProvider<?>> sandboxProviders;
    private final ExecutorService executor;
    private final long defaultTimeoutMs;

    private ExecutionEngine(Builder builder) {
        this.registry = builder.registry;
        this.mapper = new ObjectMapper();
        this.defaultTimeoutMs = builder.defaultTimeoutMs;

        // 初始化沙箱提供者
        this.sandboxProviders = new ConcurrentHashMap<>();
        this.sandboxProviders.put(RiskLevel.LOW, new DirectSandboxProvider<>());
        if (builder.policySandboxProvider != null) {
            this.sandboxProviders.put(RiskLevel.MEDIUM, builder.policySandboxProvider);
        } else {
            this.sandboxProviders.put(RiskLevel.MEDIUM, new PolicySandboxProvider<>());
        }
        if (builder.highRiskSandboxProvider != null) {
            this.sandboxProviders.put(RiskLevel.HIGH, builder.highRiskSandboxProvider);
        }

        // 线程池
        this.executor = builder.executor != null
            ? builder.executor
            : Executors.newCachedThreadPool();
    }

    /**
     * 执行一个工具调用。
     *
     * @param toolName 工具名称（与 {@link ToolRegistry} 注册的名称一致）
     * @param params   工具参数键值对
     * @return 执行结果
     */
    public ToolResult invoke(String toolName, Map<String, Object> params) {
        if (toolName == null || toolName.isBlank()) {
            return ToolResult.failure("Tool name is null or empty");
        }

        try {
            // 查找工具元数据
            ToolMetadata metadata = registry.lookup(toolName);
            RiskLevel risk = metadata.riskLevel();

            // 选择沙箱
            @SuppressWarnings("unchecked")
            SandboxProvider<Object> sandbox = (SandboxProvider<Object>) sandboxProviders.get(risk);
            if (sandbox == null) {
                return ToolResult.failure(
                    "No sandbox provider configured for risk level: " + risk
                );
            }

            // 在沙箱中执行（支持超时）
            Map<String, Object> finalParams = params != null ? params : Map.of();
            Future<Object> future = executor.submit(() -> {
                try {
                    return sandbox.executeInIsolation(() -> {
                        try {
                            return metadata.invoke(finalParams);
                        } catch (Throwable t) {
                            throw new RuntimeException("Tool method invocation failed", t);
                        }
                    });
                } catch (Exception e) {
                    throw new ExecutionException("Sandbox execution failed", e);
                }
            });

            Object result;
            try {
                result = future.get(defaultTimeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                return ToolResult.timeout("Tool execution timed out after " + defaultTimeoutMs + "ms: " + toolName);
            }

            // 包装结果
            return wrapResult(metadata, result);

        } catch (IllegalArgumentException e) {
            // 工具未注册
            return ToolResult.failure("Tool not found: " + toolName + " - " + e.getMessage());
        } catch (ExecutionException e) {
            // 工具执行时内部异常
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return wrapError(toolName, cause);
        } catch (Throwable e) {
            // 其他意外异常
            return wrapError(toolName, e);
        }
    }

    /**
     * 将方法返回值包装为成功的 ToolResult。
     */
    private ToolResult wrapResult(ToolMetadata metadata, Object result) {
        String summary;
        String rawData;

        if (result == null) {
            summary = "Tool [" + metadata.name() + "] executed successfully (void return)";
            rawData = "null";
        } else if (result instanceof String) {
            summary = "Tool [" + metadata.name() + "] returned: " + truncate(result.toString(), 200);
            rawData = (String) result;
        } else {
            try {
                rawData = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
                summary = "Tool [" + metadata.name() + "] returned structured data";
            } catch (Exception e) {
                rawData = result.toString();
                summary = "Tool [" + metadata.name() + "] returned: " + truncate(rawData, 200);
            }
        }

        return ToolResult.builder()
            .status(ToolResult.Status.SUCCESS)
            .summary(summary)
            .rawData(rawData)
            .metadata(Map.of(
                "toolName", metadata.name(),
                "riskLevel", metadata.riskLevel().name()
            ))
            .build();
    }

    /**
     * 将执行异常包装为描述性错误的 ToolResult。
     * <p>
     * 对应设计文档 §6.2 错误处理的语义化：
     * 不直接返回 Java StackTrace，而是转换为 LLM 可理解的描述性错误。
     */
    private ToolResult wrapError(String toolName, Throwable error) {
        String errorMessage = error.getMessage();
        String causeMessage = error.getCause() != null ? error.getCause().getMessage() : null;

        // 构建 LLM 友好的错误描述
        String description;
        if (errorMessage != null && causeMessage != null) {
            description = "Tool [" + toolName + "] execution failed: " + errorMessage
                + " (cause: " + causeMessage + ")";
        } else if (errorMessage != null) {
            description = "Tool [" + toolName + "] execution failed: " + errorMessage;
        } else {
            description = "Tool [" + toolName + "] execution failed with unknown error: "
                + error.getClass().getSimpleName();
        }

        return ToolResult.builder()
            .status(ToolResult.Status.FAILURE)
            .summary(description)
            .rawData(error.toString())
            .metadata(Map.of(
                "toolName", toolName,
                "errorType", error.getClass().getName(),
                "needsRevision", "true"
            ))
            .build();
    }

    /**
     * 截断字符串到指定长度。
     */
    private static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    /**
     * 优雅关闭执行引擎（释放线程池等资源）。
     */
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ========== Builder ==========

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private ToolRegistry registry;
        private ExecutorService executor;
        private SandboxProvider<?> policySandboxProvider;
        private SandboxProvider<?> highRiskSandboxProvider;
        private long defaultTimeoutMs = 30_000; // 默认 30 秒超时

        private Builder() {}

        public Builder registry(ToolRegistry registry) {
            this.registry = registry;
            return this;
        }

        public Builder executor(ExecutorService executor) {
            this.executor = executor;
            return this;
        }

        public Builder policySandboxProvider(SandboxProvider<?> provider) {
            this.policySandboxProvider = provider;
            return this;
        }

        public Builder highRiskSandboxProvider(SandboxProvider<?> provider) {
            this.highRiskSandboxProvider = provider;
            return this;
        }

        public Builder defaultTimeoutMs(long timeoutMs) {
            this.defaultTimeoutMs = timeoutMs;
            return this;
        }

        public ExecutionEngine build() {
            if (registry == null) {
                throw new IllegalStateException("ToolRegistry is required");
            }
            return new ExecutionEngine(this);
        }
    }
}
