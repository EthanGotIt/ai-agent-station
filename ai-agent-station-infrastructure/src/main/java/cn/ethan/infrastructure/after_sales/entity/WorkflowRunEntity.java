package cn.ethan.infrastructure.after_sales.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * Workflow 运行实体：映射持久化的暂停、恢复和乐观锁状态。
 *
 * @author ethan
 * @date 2026-08-07
 */
@TableName("WORKFLOW_RUN")
public final class WorkflowRunEntity {

    @TableId(value = "RUN_ID", type = IdType.INPUT)
    private String runId;

    @TableField("USER_ID")
    private String userId;

    @TableField("SESSION_ID")
    private String sessionId;

    @TableField("DOMAIN_ID")
    private String domainId;

    @TableField("WORKFLOW_ID")
    private String workflowId;

    @TableField("WORKFLOW_VERSION")
    private String workflowVersion;

    @TableField("OPERATION")
    private String operation;

    @TableField("STATUS")
    private String status;

    @TableField("CHECKPOINT_ID")
    private String checkpointId;

    @TableField("VERSION")
    private Long version;

    @TableField("STATE_JSON")
    private String stateJson;

    @TableField("QUESTION_JSON")
    private String questionJson;

    @TableField("RESULT_CONTENT")
    private String resultContent;

    @TableField("CREATED_AT")
    private Instant createdAt;

    @TableField("UPDATED_AT")
    private Instant updatedAt;

    public WorkflowRunEntity() {
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getDomainId() {
        return domainId;
    }

    public void setDomainId(String domainId) {
        this.domainId = domainId;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public void setWorkflowId(String workflowId) {
        this.workflowId = workflowId;
    }

    public String getWorkflowVersion() {
        return workflowVersion;
    }

    public void setWorkflowVersion(String workflowVersion) {
        this.workflowVersion = workflowVersion;
    }

    public String getOperation() {
        return operation;
    }

    public void setOperation(String operation) {
        this.operation = operation;
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

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public String getStateJson() {
        return stateJson;
    }

    public void setStateJson(String stateJson) {
        this.stateJson = stateJson;
    }

    public String getQuestionJson() {
        return questionJson;
    }

    public void setQuestionJson(String questionJson) {
        this.questionJson = questionJson;
    }

    public String getResultContent() {
        return resultContent;
    }

    public void setResultContent(String resultContent) {
        this.resultContent = resultContent;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
