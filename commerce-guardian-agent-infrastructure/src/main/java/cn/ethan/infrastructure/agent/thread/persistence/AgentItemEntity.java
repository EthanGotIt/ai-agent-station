package cn.ethan.infrastructure.agent.thread.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * 类型职责：映射 Thread 内按 Sequence 排列的可恢复事实。
 *
 * @author ethan
 * @date 2026-08-19
 */
@TableName("AGENT_ITEM")
public final class AgentItemEntity {

    @TableId(value = "ITEM_ID", type = IdType.INPUT)
    private String itemId;
    @TableField("THREAD_ID")
    private String threadId;
    @TableField("TURN_ID")
    private String turnId;
    @TableField("SEQUENCE_NO")
    private Long sequenceNo;
    @TableField("ITEM_TYPE")
    private String itemType;
    @TableField("PAYLOAD_JSON")
    private String payloadJson;
    @TableField("CREATED_AT")
    private Instant createdAt;

    public AgentItemEntity() {
    }

    public String getItemId() { return itemId; }
    public void setItemId(String itemId) { this.itemId = itemId; }
    public String getThreadId() { return threadId; }
    public void setThreadId(String threadId) { this.threadId = threadId; }
    public String getTurnId() { return turnId; }
    public void setTurnId(String turnId) { this.turnId = turnId; }
    public Long getSequenceNo() { return sequenceNo; }
    public void setSequenceNo(Long sequenceNo) { this.sequenceNo = sequenceNo; }
    public String getItemType() { return itemType; }
    public void setItemType(String itemType) { this.itemType = itemType; }
    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
