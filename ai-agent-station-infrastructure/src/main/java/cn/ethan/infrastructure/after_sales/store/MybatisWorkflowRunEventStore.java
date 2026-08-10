package cn.ethan.infrastructure.after_sales.store;

import cn.ethan.core.workflow.model.WorkflowRunEventModel;
import cn.ethan.core.workflow.port.WorkflowRunEventStore;
import cn.ethan.infrastructure.after_sales.entity.WorkflowRunEventEntity;
import cn.ethan.infrastructure.after_sales.mapper.WorkflowRunEventMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * MyBatis Workflow 运行事件存储：每次状态变化都追加一条不含敏感上下文的事件。
 *
 * @author ethan
 * @date 2026-08-07
 */
@Component
public final class MybatisWorkflowRunEventStore implements WorkflowRunEventStore {

    private final WorkflowRunEventMapper mapper;
    private final MeterRegistry meterRegistry;

    public MybatisWorkflowRunEventStore(WorkflowRunEventMapper mapper, MeterRegistry meterRegistry) {
        this.mapper = mapper;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void append(WorkflowRunEventModel event) {
        WorkflowRunEventEntity entity = new WorkflowRunEventEntity();
        entity.setEventId(UUID.randomUUID().toString());
        entity.setRunId(event.runId());
        entity.setVersion(event.version());
        entity.setEventType(event.eventType());
        entity.setStatus(event.status().name());
        entity.setCheckpointId(event.checkpointId());
        entity.setOccurredAt(event.occurredAt());
        if (mapper.insert(entity) != 1) {
            throw new IllegalStateException("workflow run event was not stored");
        }
        meterRegistry.counter(
                "ai.agent.workflow.transitions",
                "workflow", "workflow_run",
                "event", event.eventType(),
                "status", event.status().name()
        ).increment();
    }
}
