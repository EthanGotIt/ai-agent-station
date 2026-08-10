package cn.ethan.infrastructure.memory.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * 记忆证据实体：预留原始来源索引，首期不向模型注入。
 *
 * @author ethan
 * @date 2026-08-09
 */
@TableName("AGENT_MEMORY_EVIDENCE")
public final class AgentMemoryEvidenceEntity {

    @TableId("EVIDENCE_ID")
    private String evidenceId;
    private String entryId;
    private String evidenceType;
    private String evidenceRef;
    private Instant createdAt;

    public String getEvidenceId() { return evidenceId; }
    public void setEvidenceId(String evidenceId) { this.evidenceId = evidenceId; }
    public String getEntryId() { return entryId; }
    public void setEntryId(String entryId) { this.entryId = entryId; }
    public String getEvidenceType() { return evidenceType; }
    public void setEvidenceType(String evidenceType) { this.evidenceType = evidenceType; }
    public String getEvidenceRef() { return evidenceRef; }
    public void setEvidenceRef(String evidenceRef) { this.evidenceRef = evidenceRef; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
