package cn.ethan.infrastructure.agent.thread.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * 类型职责：映射 Turn 可恢复执行位置，不存储模型 Thinking 或进程内状态。
 *
 * @author ethan
 * @date 2026-09-29
 */
@TableName("AGENT_TURN_EXECUTION_STATE")
public final class AgentTurnExecutionStateEntity {

    @TableId(value = "TURN_ID", type = IdType.INPUT)
    private String turnId;
    @TableField("ACTIVE_DURATION_MILLIS")
    private Long activeDurationMillis;
    @TableField("ACTIVE_TOOL_BATCH_ID")
    private String activeToolBatchId;
    @TableField("NEXT_TOOL_INDEX")
    private Integer nextToolIndex;
    @TableField("TOOL_CALLS_JSON")
    private String toolCallsJson;
    @TableField("VERSION_NO")
    private Long versionNo;
    @TableField("UPDATED_AT")
    private Instant updatedAt;

    public String getTurnId() { return turnId; }
    public void setTurnId(String turnId) { this.turnId = turnId; }
    public Long getActiveDurationMillis() { return activeDurationMillis; }
    public void setActiveDurationMillis(Long activeDurationMillis) { this.activeDurationMillis = activeDurationMillis; }
    public String getActiveToolBatchId() { return activeToolBatchId; }
    public void setActiveToolBatchId(String activeToolBatchId) { this.activeToolBatchId = activeToolBatchId; }
    public Integer getNextToolIndex() { return nextToolIndex; }
    public void setNextToolIndex(Integer nextToolIndex) { this.nextToolIndex = nextToolIndex; }
    public String getToolCallsJson() { return toolCallsJson; }
    public void setToolCallsJson(String toolCallsJson) { this.toolCallsJson = toolCallsJson; }
    public Long getVersionNo() { return versionNo; }
    public void setVersionNo(Long versionNo) { this.versionNo = versionNo; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
