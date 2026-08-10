package cn.ethan.infrastructure.after_sales.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * Workflow 运行事件实体：映射追加式审计表，不保存完整会话内容。
 *
 * @author ethan
 * @date 2026-08-07
 */
@TableName("WORKFLOW_RUN_EVENT")
public final class WorkflowRunEventEntity {

    @TableId(value = "EVENT_ID", type = IdType.INPUT)
    private String eventId;

    @TableField("RUN_ID")
    private String runId;

    @TableField("VERSION")
    private Long version;

    @TableField("EVENT_TYPE")
    private String eventType;

    @TableField("STATUS")
    private String status;

    @TableField("CHECKPOINT_ID")
    private String checkpointId;

    @TableField("OCCURRED_AT")
    private Instant occurredAt;

    public WorkflowRunEventEntity() {
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getCheckpointId() {
        return checkpointId;
    }

    public void setCheckpointId(String checkpointId) {
        this.checkpointId = checkpointId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }
}
