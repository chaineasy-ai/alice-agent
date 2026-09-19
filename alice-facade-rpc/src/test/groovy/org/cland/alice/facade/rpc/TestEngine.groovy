package org.cland.alice.facade.rpc

import org.cland.alice.agent.proto.event.StepEvent
import org.cland.alice.runtime.engine.EngineEvents
import org.cland.alice.runtime.engine.AgentEngine

import java.util.concurrent.CopyOnWriteArrayList

/** 最小假引擎（facade-rpc 测试专用；不依赖 core 实现）。 */
class TestEngine implements AgentEngine {

    String session = "sess-01"
    String answer = "pong"
    Closure<String> askBody = null
    StepEvent.Usage usage = StepEvent.Usage.zero()

    final List<String> prompts   = new CopyOnWriteArrayList<>()
    final List<String> feedbacks = new CopyOnWriteArrayList<>()
    volatile int cancelCount = 0
    volatile boolean cleared = false

    private final List<EngineEvents.Listener> listeners = new CopyOnWriteArrayList<>()

    @Override
    String sessionId() { session }

    @Override
    String ask(String prompt) {
        prompts << prompt
        return askBody != null ? askBody.call(prompt) : answer
    }

    @Override
    void cancel() { cancelCount++ }

    @Override
    void injectFeedback(String message) { feedbacks << message }

    @Override
    void clearMemory() { cleared = true }

    @Override
    String compactContext() { "compacted" }

    @Override
    String currentContext() { "ctx" }

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

    void fireThought(String text) { listeners.each { it.onThought(text) } }
}
