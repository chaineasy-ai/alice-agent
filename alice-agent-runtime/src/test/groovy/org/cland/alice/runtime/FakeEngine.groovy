package org.cland.alice.runtime

import org.cland.alice.agent.proto.event.StepEvent
import org.cland.alice.runtime.engine.EngineEvents
import org.cland.alice.runtime.engine.AgentEngine

import java.util.concurrent.CopyOnWriteArrayList

/**
 * 假引擎：不依赖 core 实现，供宿主/传输单测使用。
 *
 * 行为可通过 [askBody] 定制（返回文本 / 阻塞 / 抛错），事件用 [fireThought] 等触发。
 */
class FakeEngine implements AgentEngine {

    String session = "sess-01"
    String answer = "pong"
    StepEvent.Usage usage = StepEvent.Usage.zero()
    String contextText = "| ctx |"
    Closure<String> askBody = null

    final List<String> prompts   = new CopyOnWriteArrayList<>()
    final List<String> feedbacks = new CopyOnWriteArrayList<>()
    final List<String> cancelledLog = new CopyOnWriteArrayList<>()
    final List<Integer> compactCalls = new CopyOnWriteArrayList<>()
    volatile boolean cleared = false
    volatile int cancelCount = 0

    private final List<EngineEvents.Listener> listeners = new CopyOnWriteArrayList<>()

    @Override
    String sessionId() { session }

    @Override
    String ask(String prompt) {
        prompts << prompt
        if (askBody != null) return askBody.call(prompt)
        return answer
    }

    @Override
    void cancel() { cancelCount++; cancelledLog << "cancel" }

    @Override
    void injectFeedback(String message) { feedbacks << message }

    @Override
    void clearMemory() { cleared = true }

    @Override
    String compactContext() { compactCalls << 1; return "compacted-ok" }

    @Override
    String currentContext() { contextText }

    @Override
    EngineEvents events() {
        return new EngineEvents() {
            @Override
            void subscribe(EngineEvents.Listener l) { listeners << l }

            @Override
            void unsubscribe(EngineEvents.Listener l) { listeners.remove(l) }
        }
    }

    @Override
    StepEvent.Usage lastUsage() { usage }

    // ── 事件注入（模拟内核回调）──
    void fireThought(String text) { listeners.each { it.onThought(text) } }

    void fireAction(String target, Map<String, Object> params) { listeners.each { it.onAction(target, params) } }

    void fireObserve(String raw, String summary, long ms) { listeners.each { it.onObserve(raw, summary, ms) } }

    int listenerCount() { listeners.size() }
}
