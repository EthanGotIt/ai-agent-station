package cn.ethan.core.agent.event;

import java.util.function.Consumer;

/**
 * 类型职责：定义实时 Thread 事件订阅端口，供传输层恢复事实后接收后续事件。
 *
 * @author ethan
 * @date 2026-08-20
 */
public interface AgentThreadEventSubscription {

    AutoCloseable subscribe(Consumer<AgentThreadEventGateway.AgentThreadEvent> subscriber);
}
