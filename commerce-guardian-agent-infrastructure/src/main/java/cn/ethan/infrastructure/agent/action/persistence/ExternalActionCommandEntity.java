package cn.ethan.infrastructure.agent.action.persistence;

import cn.ethan.core.agent.action.ExternalActionStatusEnum;
import cn.ethan.core.agent.action.ExternalActionTypeEnum;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * 类型职责：映射具备幂等键和租约字段的外部动作命令。
 *
 * @author ethan
 * @date 2026-08-19
 */
@TableName("EXTERNAL_ACTION_COMMAND")
public final class ExternalActionCommandEntity {

    @TableId(value = "COMMAND_ID", type = IdType.INPUT)
    private String commandId;
    @TableField("RUN_ID")
    private String runId;
    @TableField("THREAD_ID")
    private String threadId;
    @TableField("TURN_ID")
    private String turnId;
    @TableField("USER_ID")
    private String userId;
    @TableField("ACTION_TYPE")
    private ExternalActionTypeEnum actionType;
    @TableField("IDEMPOTENCY_KEY")
    private String idempotencyKey;
    @TableField("PAYLOAD_JSON")
    private String payloadJson;
    @TableField("STATUS")
    private ExternalActionStatusEnum status;
    @TableField("VERSION_NO")
    private Long versionNo;
    @TableField("ATTEMPT_COUNT")
    private Integer attemptCount;
    @TableField("MAX_ATTEMPTS")
    private Integer maxAttempts;
    @TableField("RETRY_CYCLE_ATTEMPT_COUNT")
    private Integer retryCycleAttemptCount;
    @TableField("NEXT_ATTEMPT_AT")
    private Instant nextAttemptAt;
    @TableField("LEASE_OWNER")
    private String leaseOwner;
    @TableField("LEASE_UNTIL")
    private Instant leaseUntil;
    @TableField("LAST_ERROR_CODE")
    private String lastErrorCode;
    @TableField("LAST_ERROR_MESSAGE")
    private String lastErrorMessage;
    @TableField("CREATED_AT")
    private Instant createdAt;
    @TableField("UPDATED_AT")
    private Instant updatedAt;
    @TableField("COMPLETED_AT")
    private Instant completedAt;

    public ExternalActionCommandEntity() {
    }

    public String getCommandId() { return commandId; }
    public void setCommandId(String commandId) { this.commandId = commandId; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getThreadId() { return threadId; }
    public void setThreadId(String threadId) { this.threadId = threadId; }
    public String getTurnId() { return turnId; }
    public void setTurnId(String turnId) { this.turnId = turnId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public ExternalActionTypeEnum getActionType() { return actionType; }
    public void setActionType(ExternalActionTypeEnum actionType) { this.actionType = actionType; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }
    public ExternalActionStatusEnum getStatus() { return status; }
    public void setStatus(ExternalActionStatusEnum status) { this.status = status; }
    public Long getVersionNo() { return versionNo; }
    public void setVersionNo(Long versionNo) { this.versionNo = versionNo; }
    public Integer getAttemptCount() { return attemptCount; }
    public void setAttemptCount(Integer attemptCount) { this.attemptCount = attemptCount; }
    public Integer getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(Integer maxAttempts) { this.maxAttempts = maxAttempts; }
    public Integer getRetryCycleAttemptCount() { return retryCycleAttemptCount; }
    public void setRetryCycleAttemptCount(Integer retryCycleAttemptCount) { this.retryCycleAttemptCount = retryCycleAttemptCount; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(Instant nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }
    public String getLeaseOwner() { return leaseOwner; }
    public void setLeaseOwner(String leaseOwner) { this.leaseOwner = leaseOwner; }
    public Instant getLeaseUntil() { return leaseUntil; }
    public void setLeaseUntil(Instant leaseUntil) { this.leaseUntil = leaseUntil; }
    public String getLastErrorCode() { return lastErrorCode; }
    public void setLastErrorCode(String lastErrorCode) { this.lastErrorCode = lastErrorCode; }
    public String getLastErrorMessage() { return lastErrorMessage; }
    public void setLastErrorMessage(String lastErrorMessage) { this.lastErrorMessage = lastErrorMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
}
