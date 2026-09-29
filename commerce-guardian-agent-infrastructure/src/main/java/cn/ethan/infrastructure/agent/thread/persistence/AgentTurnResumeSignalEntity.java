package cn.ethan.infrastructure.agent.thread.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * 类型职责：映射待处理和已应用的 Turn 恢复信号。
 *
 * @author ethan
 * @date 2026-09-29
 */
@TableName("AGENT_TURN_RESUME_SIGNAL")
public final class AgentTurnResumeSignalEntity {

    @TableId(value = "SIGNAL_ID", type = IdType.INPUT)
    private String signalId;
    @TableField("TURN_ID")
    private String turnId;
    @TableField("USER_ID")
    private String userId;
    @TableField("REQUEST_ID")
    private String requestId;
    @TableField("SIGNAL_KIND")
    private String signalKind;
    @TableField("INTERACTION_ID")
    private String interactionId;
    @TableField("EXPECTED_INTERACTION_VERSION")
    private Long expectedInteractionVersion;
    @TableField("PAYLOAD_JSON")
    private String payloadJson;
    @TableField("ITEM_ID")
    private String itemId;
    @TableField("STATUS")
    private String status;
    @TableField("VERSION_NO")
    private Long versionNo;
    @TableField("CREATED_AT")
    private Instant createdAt;
    @TableField("APPLIED_AT")
    private Instant appliedAt;

    public String getSignalId() { return signalId; }
    public void setSignalId(String signalId) { this.signalId = signalId; }
    public String getTurnId() { return turnId; }
    public void setTurnId(String turnId) { this.turnId = turnId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getSignalKind() { return signalKind; }
    public void setSignalKind(String signalKind) { this.signalKind = signalKind; }
    public String getInteractionId() { return interactionId; }
    public void setInteractionId(String interactionId) { this.interactionId = interactionId; }
    public Long getExpectedInteractionVersion() { return expectedInteractionVersion; }
    public void setExpectedInteractionVersion(Long expectedInteractionVersion) { this.expectedInteractionVersion = expectedInteractionVersion; }
    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }
    public String getItemId() { return itemId; }
    public void setItemId(String itemId) { this.itemId = itemId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getVersionNo() { return versionNo; }
    public void setVersionNo(Long versionNo) { this.versionNo = versionNo; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getAppliedAt() { return appliedAt; }
    public void setAppliedAt(Instant appliedAt) { this.appliedAt = appliedAt; }
}
