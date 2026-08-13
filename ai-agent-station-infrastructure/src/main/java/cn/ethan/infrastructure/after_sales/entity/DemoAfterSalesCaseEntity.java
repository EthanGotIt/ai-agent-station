package cn.ethan.infrastructure.after_sales.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 演示售后申请实体：与 WorkflowRun 分离保存用户可查询的售后事实。
 *
 * @author ethan
 * @date 2026-08-10
 */
@TableName("DEMO_AFTER_SALES_CASE")
public final class DemoAfterSalesCaseEntity {

    @TableId(value = "CASE_ID", type = IdType.INPUT)
    private String caseId;
    @TableField("WORKFLOW_RUN_ID")
    private String workflowRunId;
    @TableField("USER_ID")
    private String userId;
    @TableField("ORDER_ID")
    private String orderId;
    @TableField("REQUEST_TYPE")
    private String requestType;
    @TableField("REFUND_REASON")
    private String refundReason;
    @TableField("DESCRIPTION")
    private String description;
    @TableField("HANDLING_MODE")
    private String handlingMode;
    @TableField("STATUS")
    private String status;
    @TableField("AMOUNT")
    private BigDecimal amount;
    @TableField("CURRENCY")
    private String currency;
    @TableField("REFUND_ID")
    private String refundId;
    @TableField("OPERATOR_ID")
    private String operatorId;
    @TableField("DECISION_ID")
    private String decisionId;
    @TableField("DECISION_NOTE")
    private String decisionNote;
    @TableField("REVIEWED_AT")
    private Instant reviewedAt;
    @TableField("FAILURE_CODE")
    private String failureCode;
    @TableField("VERSION")
    private Long version;
    @TableField("CREATED_AT")
    private Instant createdAt;
    @TableField("UPDATED_AT")
    private Instant updatedAt;

    public String getCaseId() { return caseId; }
    public void setCaseId(String caseId) { this.caseId = caseId; }
    public String getWorkflowRunId() { return workflowRunId; }
    public void setWorkflowRunId(String workflowRunId) { this.workflowRunId = workflowRunId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
    public String getRequestType() { return requestType; }
    public void setRequestType(String requestType) { this.requestType = requestType; }
    public String getRefundReason() { return refundReason; }
    public void setRefundReason(String refundReason) { this.refundReason = refundReason; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getHandlingMode() { return handlingMode; }
    public void setHandlingMode(String handlingMode) { this.handlingMode = handlingMode; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getRefundId() { return refundId; }
    public void setRefundId(String refundId) { this.refundId = refundId; }
    public String getOperatorId() { return operatorId; }
    public void setOperatorId(String operatorId) { this.operatorId = operatorId; }
    public String getDecisionId() { return decisionId; }
    public void setDecisionId(String decisionId) { this.decisionId = decisionId; }
    public String getDecisionNote() { return decisionNote; }
    public void setDecisionNote(String decisionNote) { this.decisionNote = decisionNote; }
    public Instant getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(Instant reviewedAt) { this.reviewedAt = reviewedAt; }
    public String getFailureCode() { return failureCode; }
    public void setFailureCode(String failureCode) { this.failureCode = failureCode; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
