package org.cland.alice.core.agent.kernel.graph;

/**
 * 安全点回调（§3.2 ⑥）— 解释器在每个节点边界（{@code steps++} 之前）通知装配方，用于 WAL/Checkpoint 落点。
 *
 * <p>解释器只负责「在哪些点通知」；「是否持久化、以何频率」由装配方决定（如节流到 goal/terminal/error）。 回调抛出异常即中止遍历（可用于故障注入/恢复演练）。
 */
@FunctionalInterface
public interface CheckpointSink {

  void onSafePoint(SafePoint safePoint);
}
