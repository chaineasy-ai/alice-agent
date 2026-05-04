package org.cland.alice.tool.gateway.annotation;

/**
 * 工具的风险等级，决定执行时的沙箱策略。
 * <p>
 * 对应设计文档 §4.2 多级沙箱策略：
 * <ul>
 *   <li>{@link #LOW} — 直接执行，无副作用（只读操作、纯计算）</li>
 *   <li>{@link #MEDIUM} — 策略限制，受控的文件/网络访问</li>
 *   <li>{@link #HIGH} — 完全沙箱化，强制分发至 Docker/Wasm 隔离环境</li>
 * </ul>
 */
public enum RiskLevel {
    /** 无副作用操作，在当前 JVM 线程池直接执行 */
    LOW,
    /** 受限制操作，启用 SecurityManager/Policy 限制文件系统/网络 */
    MEDIUM,
    /** 高危操作，强制隔离到 Docker 容器或 WebAssembly 运行时 */
    HIGH
}
