package org.cland.alice.tool.gateway.sandbox;

import java.util.concurrent.Callable;

/**
 * Level 1 沙箱：直接在当前线程池执行，无隔离。
 * <p>
 * 适用于 {@link org.cland.alice.tool.gateway.annotation.RiskLevel#LOW} 的工具：
 * 只读操作、纯计算、无副作用的方法。
 */
public class DirectSandboxProvider<T> implements SandboxProvider<T> {

    @Override
    public T executeInIsolation(Callable<T> task) throws Exception {
        // 直接调用，无任何隔离
        return task.call();
    }

    @Override
    public void prewarm() {
        // 无预热逻辑
    }

    @Override
    public void cleanup() {
        // 无清理逻辑
    }
}
