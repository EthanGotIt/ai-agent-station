package cn.ethan.app.agent.api;

import cn.ethan.core.agent.event.AgentThreadEventGateway;

import java.time.Instant;

/**
 * 类型职责：表达 Thread 实时 SSE 事件。
 *
 * @author ethan
 * @date 2026-08-19
 */
public record AgentThreadEventDto(
        String eventId,
        String threadId,
        String turnId,
        String type,
        String payload,
        long sequence,
        Instant at
) {
    public static AgentThreadEventDto from(AgentThreadEventGateway.AgentThreadEvent event) {
        return new AgentThreadEventDto(event.eventId(), event.threadId(), event.turnId(), event.type(),
                event.payload(), event.sequence(), event.at());
    }
}
