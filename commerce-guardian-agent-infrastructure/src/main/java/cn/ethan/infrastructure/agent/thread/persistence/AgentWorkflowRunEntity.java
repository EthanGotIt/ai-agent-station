package cn.ethan.infrastructure.agent.thread.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * 类型职责：映射确定性 WorkflowRun 的状态和乐观版本。
 *
 * @author ethan
 * @date 2026-08-19
 */
@TableName("AGENT_WORKFLOW_RUN")
public final class AgentWorkflowRunEntity {

    @TableId(value = "RUN_ID", type = IdType.INPUT)
    private String runId;
    @TableField("THREAD_ID")
    private String threadId;
    @TableField("TURN_ID")
    private String turnId;
    @TableField("USER_ID")
    private String userId;
    @TableField("WORKFLOW_TYPE")
    private String workflowType;
    @TableField("STATUS")
    private String status;
    @TableField("VERSION_NO")
    private Long versionNo;
    @TableField("STEPS_JSON")
    private String stepsJson;
    @TableField("STATE_JSON")
    private String stateJson;
    @TableField("CREATED_AT")
    private Instant createdAt;
    @TableField("UPDATED_AT")
    private Instant updatedAt;

    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getThreadId() { return threadId; }
    public void setThreadId(String threadId) { this.threadId = threadId; }
    public String getTurnId() { return turnId; }
    public void setTurnId(String turnId) { this.turnId = turnId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getWorkflowType() { return workflowType; }
    public void setWorkflowType(String workflowType) { this.workflowType = workflowType; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getVersionNo() { return versionNo; }
    public void setVersionNo(Long versionNo) { this.versionNo = versionNo; }
    public String getStepsJson() { return stepsJson; }
    public void setStepsJson(String stepsJson) { this.stepsJson = stepsJson; }
    public String getStateJson() { return stateJson; }
    public void setStateJson(String stateJson) { this.stateJson = stateJson; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
