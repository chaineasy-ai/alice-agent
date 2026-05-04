package org.cland.alice.tool.gateway.sandbox;

import java.util.concurrent.Callable;

/**
 * 沙箱提供者接口 — 隔离工具执行环境。
 * <p>
 * 对应设计文档类图中 {@code SandboxProvider}，以及 §4.2 多级沙箱策略。
 * <p>
 * 三种实现策略：
 * <ul>
 *   <li><b>Level 1 (Direct)</b> — 只在当前线程池执行，无隔离</li>
 *   <li><b>Level 2 (Jail/Policy)</b> — 利用 Java SecurityManager 或 Policy 限制权限</li>
 *   <li><b>Level 3 (Full Sandbox)</b> — 分发到 Docker 容器或 WebAssembly 运行时执行</li>
 * </ul>
 *
 * @param <T> 返回结果类型
 */
public interface SandboxProvider<T> {

    /**
     * 在隔离环境中执行给定的任务。
     *
     * @param task 需要隔离执行的任务
     * @return 执行结果
     * @throws Exception 执行过程中抛出的任何异常
     */
    T executeInIsolation(Callable<T> task) throws Exception;

    /**
     * 预初始化隔离环境（容器预热、资源分配）。
     * 在第一次执行前或按计划调用。
     */
    default void prewarm() {
        // 默认无操作，子类可覆盖
    }

    /**
     * 清理隔离环境（容器销毁、资源释放）。
     */
    default void cleanup() {
        // 默认无操作，子类可覆盖
    }
}
