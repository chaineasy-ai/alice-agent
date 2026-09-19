/*
 * Alice Agent — 轮次事件发布器（package-private）
 */
package org.cland.alice.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Flow;
import org.cland.alice.agent.proto.event.StepEvent;

/**
 * 轮次事件发布器：**先执行、后订阅也不丢帧**。
 *
 * <p>为什么不用 {@code SubmissionPublisher}：宿主在 {@code dispatch} 时即开始执行（"一轮一锁"的 busy 判断必须同步返回），而调用方可能稍后才
 * {@code subscribe} —— 标准发布器此时已丢帧。本类内置 回放缓冲 + 需求（demand）跟踪：
 *
 * <ul>
 *   <li>帧先入缓冲，再按订阅者 demand 投递；demand 不足时排队（不丢）
 *   <li>新订阅者：先收到缓冲回放，再收完成通知（若已 complete）
 *   <li>线程安全：状态在一把锁内；下游回调串行化且异常被隔离（订阅者抛错不影响宿主）
 * </ul>
 */
final class RoundEventPublisher implements Flow.Publisher<StepEvent> {

  private final Object lock = new Object();
  private final List<StepEvent> buffer = new ArrayList<>();
  private final List<Sub> subs = new ArrayList<>();
  private boolean completed;

  /**
   * 追加一帧（完成后不再接受）。
   *
   * @param event 事件帧
   */
  void emit(StepEvent event) {
    List<Sub> targets;
    synchronized (lock) {
      if (completed) {
        return;
      }
      buffer.add(event);
      targets = List.copyOf(subs);
    }
    for (Sub s : targets) {
      s.push(event);
    }
  }

  /** 完成本流（幂等）。 */
  void complete() {
    List<Sub> targets;
    synchronized (lock) {
      if (completed) {
        return;
      }
      completed = true;
      targets = List.copyOf(subs);
    }
    for (Sub s : targets) {
      s.complete();
    }
  }

  @Override
  public void subscribe(Flow.Subscriber<? super StepEvent> subscriber) {
    Objects.requireNonNull(subscriber, "subscriber must not be null");
    Sub sub = new Sub(subscriber);
    // 关键：**回放与完成态必须在订阅锁内先入队/标记**，再 onSubscribe —
    // 否则 worker 可能在 onSubscribe 与回放 push 之间 push 末帧并 complete，
    // 把回放帧当成"已终止后的迟到帧"丢掉（实测：100 轮里偶发丢 SUMMARY/DONE ✗）。
    synchronized (lock) {
      sub.enqueueAll(buffer);
      if (completed) {
        sub.markDone();
      }
      subs.add(sub); // 注册后 worker 的 live push 才可能到达（顺序必然在回放之后 ✓）
    }
    subscriber.onSubscribe(sub);
    sub.drain(); // 订阅者未在 onSubscribe 内 request 时，后续 request 会再次 drain
  }

  /** 单订阅者：需求跟踪 + 排队 + 串行化下游回调。 */
  private final class Sub implements Flow.Subscription {

    private final Flow.Subscriber<? super StepEvent> downstream;
    private final Object guard = new Object();
    private final Object deliver = new Object();
    private final Deque<StepEvent> pending = new ArrayDeque<>();
    private long demand;
    private boolean cancelled;
    private boolean upstreamDone;
    private boolean terminated;

    Sub(Flow.Subscriber<? super StepEvent> downstream) {
      this.downstream = downstream;
    }

    /**
     * 仅入队（不投递）——供 {@link #subscribe} 在 onSubscribe 之前灌回放帧用（规范：onNext 不得先于 onSubscribe）。
     *
     * @param events 回放帧
     */
    void enqueueAll(List<StepEvent> events) {
      synchronized (guard) {
        if (cancelled || terminated) {
          return;
        }
        pending.addAll(events);
      }
    }

    /** 标记上游完成（不投递；由 {@link #drain()} 按序收口）。 */
    void markDone() {
      synchronized (guard) {
        upstreamDone = true;
      }
    }

    void push(StepEvent event) {
      synchronized (guard) {
        if (cancelled || terminated) {
          return;
        }
        pending.add(event);
      }
      drain();
    }

    void complete() {
      markDone();
      drain();
    }

    @Override
    public void request(long n) {
      if (n <= 0) {
        fail(new IllegalArgumentException("non-positive request: " + n));
        return;
      }
      synchronized (guard) {
        if (cancelled || terminated) {
          return;
        }
        demand = demand + n < 0 ? Long.MAX_VALUE : demand + n;
      }
      drain();
    }

    @Override
    public void cancel() {
      synchronized (guard) {
        cancelled = true;
        pending.clear();
      }
    }

    private void drain() {
      synchronized (deliver) {
        while (true) {
          StepEvent next = null;
          boolean finish = false;
          synchronized (guard) {
            if (cancelled || terminated) {
              return;
            }
            if (demand > 0 && !pending.isEmpty()) {
              demand--;
              next = pending.poll();
            } else if (upstreamDone && pending.isEmpty()) {
              terminated = true;
              finish = true;
            } else {
              return;
            }
          }
          try {
            if (next != null) {
              downstream.onNext(next);
            } else {
              downstream.onComplete();
            }
          } catch (RuntimeException ignored) {
            // 订阅者异常隔离：不打断宿主轮次（帧已入缓冲，其他订阅者不受影响）
          }
          if (finish) {
            return;
          }
        }
      }
    }

    private void fail(Throwable error) {
      synchronized (guard) {
        if (cancelled || terminated) {
          return;
        }
        terminated = true;
        pending.clear();
      }
      try {
        downstream.onError(error);
      } catch (RuntimeException ignored) {
        // 同样隔离
      }
    }
  }
}
