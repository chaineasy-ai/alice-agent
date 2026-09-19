/*
 * Alice Agent — 进程内传输（直调）
 *
 * 面向同进程调用者（TUI / CLI / 测试）：不做序列化，直接把命令交给宿主分发端口。
 */
package org.cland.alice.runtime.transport;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.cland.alice.agent.proto.AgentCommand;
import org.cland.alice.agent.proto.event.StepEvent;
import org.cland.alice.agent.proto.port.AgentCommandDispatcher;
import org.cland.alice.agent.proto.port.SessionTransport;

/**
 * InProcess 传输 — 同一 JVM 内直调宿主（**无序列化**，热路径首选）。
 *
 * <p>用法：
 *
 * <pre>{@code
 * var t = new InProcessTransport();
 * t.start(host);                      // 绑定宿主
 * var frames = t.await(cmd, 60_000);  // CLI/测试：阻塞收集到 DONE
 * t.close();
 * }</pre>
 */
public final class InProcessTransport implements SessionTransport {

  private volatile AgentCommandDispatcher dispatcher;

  @Override
  public String name() {
    return "inprocess";
  }

  @Override
  public void start(AgentCommandDispatcher dispatcher) {
    this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher must not be null");
  }

  @Override
  public void close() {
    dispatcher = null;
  }

  /**
   * 直调宿主（调用方自行订阅；不阻塞）。
   *
   * @param cmd 命令
   * @return 事件发布者
   * @throws IllegalStateException 未 {@link #start} 或已 {@link #close}
   */
  public Flow.Publisher<StepEvent> dispatch(AgentCommand cmd) {
    AgentCommandDispatcher d = dispatcher;
    if (d == null) {
      throw new IllegalStateException("InProcessTransport 未启动：先 start(dispatcher)");
    }
    return d.dispatch(cmd);
  }

  /**
   * 阻塞收集一轮的全部帧（到 {@code DONE} 或超时）——CLI/测试便捷方法。
   *
   * @param cmd 命令
   * @param timeoutMs 超时（毫秒）
   * @return 帧列表（含最后的 DONE）
   * @throws InterruptedException 等待被中断
   * @throws IllegalStateException 超时（未在预算内收口）
   */
  public List<StepEvent> await(AgentCommand cmd, long timeoutMs) throws InterruptedException {
    Objects.requireNonNull(cmd, "cmd must not be null");
    Flow.Publisher<StepEvent> publisher = dispatch(cmd);
    List<StepEvent> frames = Collections.synchronizedList(new ArrayList<>());
    CountDownLatch done = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    publisher.subscribe(
        new Flow.Subscriber<>() {
          @Override
          public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
          }

          @Override
          public void onNext(StepEvent item) {
            frames.add(item);
          }

          @Override
          public void onError(Throwable throwable) {
            failure.set(throwable);
            done.countDown();
          }

          @Override
          public void onComplete() {
            done.countDown();
          }
        });
    if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
      throw new IllegalStateException(
          "等待轮次超时（" + timeoutMs + "ms）：已收 " + frames.size() + " 帧，未收到 DONE");
    }
    if (failure.get() != null) {
      throw new IllegalStateException("轮次失败", failure.get());
    }
    return new ArrayList<>(frames);
  }
}
