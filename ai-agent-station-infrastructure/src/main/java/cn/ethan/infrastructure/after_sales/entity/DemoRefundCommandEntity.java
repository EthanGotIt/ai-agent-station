package cn.ethan.infrastructure.after_sales.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 演示退款命令实体：映射以 WorkflowRun 为幂等键的本地退款记录。
 *
 * @author ethan
 * @date 2026-08-07
 */
@TableName("DEMO_REFUND_COMMAND")
public final class DemoRefundCommandEntity {

    @TableId(value = "REFUND_ID", type = IdType.INPUT)
    private String refundId;

    @TableField("WORKFLOW_RUN_ID")
    private String workflowRunId;

    @TableField("CASE_ID")
    private String caseId;

    @TableField("ORDER_ID")
    private String orderId;

    @TableField("USER_ID")
    private String userId;

    @TableField("REFUND_REASON")
    private String refundReason;

    @TableField("AMOUNT")
    private BigDecimal amount;

    @TableField("CURRENCY")
    private String currency;

    @TableField("STATUS")
    private String status;

    @TableField("RETRY_ID")
    private String retryId;

    @TableField("ATTEMPT_COUNT")
    private Integer attemptCount;

    @TableField("NEXT_ATTEMPT_AT")
    private Instant nextAttemptAt;

    @TableField("LEASE_UNTIL")
    private Instant leaseUntil;

    @TableField("FAILURE_CODE")
    private String failureCode;

    @TableField("VERSION")
    private Long version;

    @TableField("CREATED_AT")
    private Instant createdAt;

    @TableField("UPDATED_AT")
    private Instant updatedAt;

    public DemoRefundCommandEntity() {
    }

    public String getRefundId() {
        return refundId;
    }

    public void setRefundId(String refundId) {
        this.refundId = refundId;
    }

    public String getWorkflowRunId() {
        return workflowRunId;
    }

    public void setWorkflowRunId(String workflowRunId) {
        this.workflowRunId = workflowRunId;
    }

    public String getCaseId() {
        return caseId;
    }

    public void setCaseId(String caseId) {
        this.caseId = caseId;
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getRefundReason() {
        return refundReason;
    }

    public void setRefundReason(String refundReason) {
        this.refundReason = refundReason;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getRetryId() { return retryId; }
    public void setRetryId(String retryId) { this.retryId = retryId; }
    public Integer getAttemptCount() { return attemptCount; }
    public void setAttemptCount(Integer attemptCount) { this.attemptCount = attemptCount; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(Instant nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }
    public Instant getLeaseUntil() { return leaseUntil; }
    public void setLeaseUntil(Instant leaseUntil) { this.leaseUntil = leaseUntil; }
    public String getFailureCode() { return failureCode; }
    public void setFailureCode(String failureCode) { this.failureCode = failureCode; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
