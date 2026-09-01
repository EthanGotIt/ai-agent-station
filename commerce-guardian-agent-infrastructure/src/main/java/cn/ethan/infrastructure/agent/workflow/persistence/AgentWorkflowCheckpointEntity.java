package cn.ethan.infrastructure.agent.workflow.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * 类型职责：映射固定 Workflow 的人工执行确认事实。
 *
 * @author ethan
 * @date 2026-08-27
 */
@TableName("AGENT_WORKFLOW_CHECKPOINT")
public final class AgentWorkflowCheckpointEntity {

    @TableId(value = "CHECKPOINT_ID", type = IdType.INPUT)
    private String checkpointId;
    @TableField("RUN_ID")
    private String runId;
    @TableField("THREAD_ID")
    private String threadId;
    @TableField("TURN_ID")
    private String turnId;
    @TableField("USER_ID")
    private String userId;
    @TableField("NODE_ID")
    private String nodeId;
    @TableField("ACTION_TYPE")
    private String actionType;
    @TableField("ORDER_ID")
    private String orderId;
    @TableField("IMPACT_SUMMARY")
    private String impactSummary;
    @TableField("FACTS_FINGERPRINT")
    private String factsFingerprint;
    @TableField("VERSION_NO")
    private Long versionNo;
    @TableField("STATUS")
    private String status;
    @TableField("DECISION")
    private String decision;
    @TableField("CREATED_AT")
    private Instant createdAt;
    @TableField("DECIDED_AT")
    private Instant decidedAt;

    public String getCheckpointId() { return checkpointId; }
    public void setCheckpointId(String checkpointId) { this.checkpointId = checkpointId; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getThreadId() { return threadId; }
    public void setThreadId(String threadId) { this.threadId = threadId; }
    public String getTurnId() { return turnId; }
    public void setTurnId(String turnId) { this.turnId = turnId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }
    public String getActionType() { return actionType; }
    public void setActionType(String actionType) { this.actionType = actionType; }
    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
    public String getImpactSummary() { return impactSummary; }
    public void setImpactSummary(String impactSummary) { this.impactSummary = impactSummary; }
    public String getFactsFingerprint() { return factsFingerprint; }
    public void setFactsFingerprint(String factsFingerprint) { this.factsFingerprint = factsFingerprint; }
    public Long getVersionNo() { return versionNo; }
    public void setVersionNo(Long versionNo) { this.versionNo = versionNo; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getDecision() { return decision; }
    public void setDecision(String decision) { this.decision = decision; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getDecidedAt() { return decidedAt; }
    public void setDecidedAt(Instant decidedAt) { this.decidedAt = decidedAt; }
}
