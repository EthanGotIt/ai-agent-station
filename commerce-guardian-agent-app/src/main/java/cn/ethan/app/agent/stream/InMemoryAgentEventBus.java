package cn.ethan.app.agent.stream;

import cn.ethan.core.agent.event.AgentThreadEventGateway;
import cn.ethan.core.agent.event.AgentThreadEventSubscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 类型职责：在当前进程内广播 Thread 事件，Item 事实仍由存储端口负责持久化。
 *
 * @author ethan
 * @date 2026-08-20
 */
public final class InMemoryAgentEventBus implements AgentThreadEventGateway, AgentThreadEventSubscription {
    private static final Logger LOGGER = LoggerFactory.getLogger(InMemoryAgentEventBus.class);
    private final CopyOnWriteArrayList<Consumer<AgentThreadEventGateway.AgentThreadEvent>> subscribers =
            new CopyOnWriteArrayList<>();

    @Override
    public void publish(AgentThreadEventGateway.AgentThreadEvent event) {
        for (Consumer<AgentThreadEventGateway.AgentThreadEvent> subscriber : subscribers) {
            try {
                subscriber.accept(event);
            } catch (RuntimeException failure) {
                // 单个断开的 SSE 订阅不影响其他订阅者
                LOGGER.debug("Thread event subscriber failed, eventType={}, errorType={}",
                        event.type(), failure.getClass().getSimpleName());
            }
        }
    }

    public AutoCloseable subscribe(Consumer<AgentThreadEventGateway.AgentThreadEvent> subscriber) {
        subscribers.add(subscriber);
        return () -> subscribers.remove(subscriber);
    }
}
