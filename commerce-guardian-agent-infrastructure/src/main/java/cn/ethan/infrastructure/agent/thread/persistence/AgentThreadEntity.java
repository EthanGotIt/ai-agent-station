package cn.ethan.infrastructure.agent.thread.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * 类型职责：映射 Thread 的持久化元数据和事实序号。
 *
 * @author ethan
 * @date 2026-08-19
 */
@TableName("AGENT_THREAD")
public final class AgentThreadEntity {

    @TableId(value = "THREAD_ID", type = IdType.INPUT)
    private String threadId;
    @TableField("USER_ID")
    private String userId;
    @TableField("TITLE")
    private String title;
    @TableField("STATUS")
    private String status;
    @TableField("CONTEXT_TYPE")
    private String contextType;
    @TableField("CONTEXT_ID")
    private String contextId;
    @TableField("NEXT_SEQUENCE")
    private Long nextSequence;
    @TableField("CREATED_AT")
    private Instant createdAt;
    @TableField("UPDATED_AT")
    private Instant updatedAt;

    public AgentThreadEntity() {
    }

    public String getThreadId() { return threadId; }
    public void setThreadId(String threadId) { this.threadId = threadId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getContextType() { return contextType; }
    public void setContextType(String contextType) { this.contextType = contextType; }
    public String getContextId() { return contextId; }
    public void setContextId(String contextId) { this.contextId = contextId; }
    public Long getNextSequence() { return nextSequence; }
    public void setNextSequence(Long nextSequence) { this.nextSequence = nextSequence; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
