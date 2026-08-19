package cn.ethan.core.agent.thread.support;

import cn.ethan.core.agent.thread.port.AgentThreadEventGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 类型职责：在当前进程内广播 Thread 事件，Item 事实仍由存储端口负责持久化。
 *
 * @author ethan
 * @date 2026-08-19
 */
public final class InMemoryAgentThreadEventGateway implements AgentThreadEventGateway {
    private static final Logger LOGGER = LoggerFactory.getLogger(InMemoryAgentThreadEventGateway.class);
    private final CopyOnWriteArrayList<Consumer<AgentThreadEvent>> subscribers = new CopyOnWriteArrayList<>();

    @Override
    public void publish(AgentThreadEvent event) {
        for (Consumer<AgentThreadEvent> subscriber : subscribers) {
            try {
                subscriber.accept(event);
            } catch (RuntimeException failure) {
                // 单个断开的 SSE 订阅不影响其他订阅者。
                LOGGER.debug("Thread event subscriber failed, eventType={}, errorType={}",
                        event.type(), failure.getClass().getSimpleName());
            }
        }
    }

    public AutoCloseable subscribe(Consumer<AgentThreadEvent> subscriber) {
        subscribers.add(subscriber);
        return () -> subscribers.remove(subscriber);
    }
}
