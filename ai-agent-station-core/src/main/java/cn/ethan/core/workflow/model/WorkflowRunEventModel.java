package cn.ethan.core.workflow.model;

import cn.ethan.core.workflow.enums.WorkflowRunStatusEnum;

import java.time.Instant;

/**
 * Workflow 运行事件模型：以追加式事件记录状态变化，不记录用户完整输入或模型提示词。
 *
 * @author ethan
 * @date 2026-08-07
 */
public record WorkflowRunEventModel(
        String runId,
        long version,
        String eventType,
        WorkflowRunStatusEnum status,
        String checkpointId,
        Instant occurredAt
) {

    public WorkflowRunEventModel {
        if (runId == null || runId.isBlank() || eventType == null || eventType.isBlank()
                || status == null || occurredAt == null || version < 0) {
            throw new IllegalArgumentException("workflow run event is incomplete");
        }
        checkpointId = checkpointId == null ? "" : checkpointId.strip();
    }
}
