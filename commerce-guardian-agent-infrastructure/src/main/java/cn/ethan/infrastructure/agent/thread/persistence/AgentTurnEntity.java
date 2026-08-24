package cn.ethan.infrastructure.agent.thread.persistence;

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
    @TableField("INPUT_KIND")
    private String inputKind;
    @TableField("ORDER_ACTION_JSON")
    private String orderActionJson;
    @TableField("STATUS")
    private String status;
    @TableField("QUEUE_POSITION")
    private Integer queuePosition;
    @TableField("WORKFLOW_RUN_ID")
    private String workflowRunId;
    @TableField("WORKFLOW_QUESTION_ID")
    private String workflowQuestionId;
    @TableField("WORKFLOW_CHECKPOINT_ID")
    private String workflowCheckpointId;
    @TableField("WORKFLOW_QUESTION_VERSION")
    private Long workflowQuestionVersion;
    @TableField("WORKFLOW_ANSWERS_JSON")
    private String workflowAnswersJson;
    @TableField("ERROR_CODE")
    private String errorCode;
    @TableField("CREATED_AT")
    private Instant createdAt;
    @TableField("STARTED_AT")
    private Instant startedAt;
    @TableField("FINISHED_AT")
    private Instant finishedAt;
    @TableField("VERSION_NO")
    private Long versionNo;

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
    public String getInputKind() { return inputKind; }
    public void setInputKind(String inputKind) { this.inputKind = inputKind; }
    public String getOrderActionJson() { return orderActionJson; }
    public void setOrderActionJson(String orderActionJson) { this.orderActionJson = orderActionJson; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getQueuePosition() { return queuePosition; }
    public void setQueuePosition(Integer queuePosition) { this.queuePosition = queuePosition; }
    public String getWorkflowRunId() { return workflowRunId; }
    public void setWorkflowRunId(String workflowRunId) { this.workflowRunId = workflowRunId; }
    public String getWorkflowQuestionId() { return workflowQuestionId; }
    public void setWorkflowQuestionId(String workflowQuestionId) { this.workflowQuestionId = workflowQuestionId; }
    public String getWorkflowCheckpointId() { return workflowCheckpointId; }
    public void setWorkflowCheckpointId(String workflowCheckpointId) { this.workflowCheckpointId = workflowCheckpointId; }
    public Long getWorkflowQuestionVersion() { return workflowQuestionVersion; }
    public void setWorkflowQuestionVersion(Long workflowQuestionVersion) { this.workflowQuestionVersion = workflowQuestionVersion; }
    public String getWorkflowAnswersJson() { return workflowAnswersJson; }
    public void setWorkflowAnswersJson(String workflowAnswersJson) { this.workflowAnswersJson = workflowAnswersJson; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
    public Long getVersionNo() { return versionNo; }
    public void setVersionNo(Long versionNo) { this.versionNo = versionNo; }
}
