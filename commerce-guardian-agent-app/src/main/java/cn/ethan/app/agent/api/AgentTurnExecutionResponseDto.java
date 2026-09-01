package cn.ethan.app.agent.api;

import cn.ethan.core.agent.execution.AgentExecutionTimelineModel;

import java.time.Instant;
import java.util.List;

/**
 * 类型职责：表达从 Item 事实回放的 Turn 执行轨迹，不包含原始 Thinking。
 *
 * @author ethan
 * @date 2026-08-20
 */
public record AgentTurnExecutionResponseDto(
        String turnId,
        String threadId,
        String status,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        List<AgentItemDto> timeline
) {

    public static AgentTurnExecutionResponseDto from(AgentExecutionTimelineModel model) {
        var turn = model.turn();
        return new AgentTurnExecutionResponseDto(turn.turnId(), turn.threadId(), turn.status().name(),
                turn.createdAt(), turn.startedAt(), turn.finishedAt(),
                model.items().stream().map(AgentItemDto::from).toList());
    }
}
