package cn.ethan.ai.domain.agent.model.entity;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Agent 单次运行追踪实体，当前版本先在内存中记录事件。
 */
public class AgentRunTraceEntity {

    @Getter
    private final String runId = UUID.randomUUID().toString();

    private final List<AgentRunEventEntity> events = new ArrayList<>();

    public AgentRunEventEntity record(String eventType, String stepId, Integer step, long startTime, String summary, String error) {
        long endTime = System.currentTimeMillis();
        AgentRunEventEntity event = AgentRunEventEntity.builder()
                .runId(runId)
                .eventType(eventType)
                .stepId(stepId)
                .step(step)
                .startTime(startTime)
                .endTime(endTime)
                .costMillis(endTime - startTime)
                .summary(summary)
                .error(error)
                .build();
        events.add(event);
        return event;
    }

    public List<AgentRunEventEntity> events() {
        return Collections.unmodifiableList(events);
    }
}
