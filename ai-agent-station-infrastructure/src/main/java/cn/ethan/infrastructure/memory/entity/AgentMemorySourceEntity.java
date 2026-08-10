package cn.ethan.infrastructure.memory.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * 记忆来源实体：映射 AGENT_MEMORY_SOURCE。
 *
 * @author ethan
 * @date 2026-08-09
 */
@TableName("AGENT_MEMORY_SOURCE")
public final class AgentMemorySourceEntity {

    @TableId("SOURCE_ID")
    private String sourceId;
    private String userId;
    private String sessionId;
    private String requestId;
    private String sourceType;
    private Instant createdAt;

    public String getSourceId() { return sourceId; }
    public void setSourceId(String sourceId) { this.sourceId = sourceId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
