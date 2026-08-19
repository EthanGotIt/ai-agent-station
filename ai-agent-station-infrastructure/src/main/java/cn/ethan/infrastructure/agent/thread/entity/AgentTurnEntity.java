package cn.ethan.infrastructure.agent.thread.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * 类型职责：映射一次 Agent Turn 的执行状态和幂等请求信息。
 *
 * @author ethan
 * @date 2026-08-19
 */
@TableName("AGENT_TURN")
public final class AgentTurnEntity {

    @TableId(value = "TURN_ID", type = IdType.INPUT)
    private String turnId;
    @TableField("THREAD_ID")
    private String threadId;
    @TableField("USER_ID")
    private String userId;
    @TableField("CLIENT_REQUEST_ID")
    private String clientRequestId;
    @TableField("INPUT_TEXT")
    private String inputText;
    @TableField("STATUS")
    private String status;
    @TableField("QUEUE_POSITION")
    private Integer queuePosition;
    @TableField("WORKFLOW_RUN_ID")
    private String workflowRunId;
    @TableField("ERROR_CODE")
    private String errorCode;
    @TableField("CREATED_AT")
    private Instant createdAt;
    @TableField("STARTED_AT")
    private Instant startedAt;
    @TableField("FINISHED_AT")
    private Instant finishedAt;

    public AgentTurnEntity() {
    }

    public String getTurnId() { return turnId; }
    public void setTurnId(String turnId) { this.turnId = turnId; }
    public String getThreadId() { return threadId; }
    public void setThreadId(String threadId) { this.threadId = threadId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getClientRequestId() { return clientRequestId; }
    public void setClientRequestId(String clientRequestId) { this.clientRequestId = clientRequestId; }
    public String getInputText() { return inputText; }
    public void setInputText(String inputText) { this.inputText = inputText; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getQueuePosition() { return queuePosition; }
    public void setQueuePosition(Integer queuePosition) { this.queuePosition = queuePosition; }
    public String getWorkflowRunId() { return workflowRunId; }
    public void setWorkflowRunId(String workflowRunId) { this.workflowRunId = workflowRunId; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
}
