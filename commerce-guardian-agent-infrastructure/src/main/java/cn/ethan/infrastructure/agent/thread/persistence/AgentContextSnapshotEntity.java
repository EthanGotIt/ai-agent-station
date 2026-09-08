package cn.ethan.infrastructure.agent.thread.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * 类型职责：映射 Thread 的版本化上下文摘要快照。
 *
 * @author ethan
 * @date 2026-08-19
 */
@TableName("AGENT_CONTEXT_SNAPSHOT")
public final class AgentContextSnapshotEntity {

    @TableId(value = "SNAPSHOT_ID", type = IdType.INPUT)
    private String snapshotId;
    @TableField("THREAD_ID")
    private String threadId;
    @TableField("THROUGH_SEQUENCE")
    private Long throughSequence;
    @TableField("VERSION_NO")
    private Long versionNo;
    @TableField("ESTIMATED_TOKENS")
    private Integer estimatedTokens;
    @TableField("SUMMARY")
    private String summary;
    @TableField("CREATED_AT")
    private Instant createdAt;
    @TableField("FORMAT_VERSION")
    private Integer formatVersion;
    @TableField("BASE_SNAPSHOT_ID")
    private String baseSnapshotId;
    @TableField("SOURCE_FROM_SEQUENCE")
    private Long sourceFromSequence;
    @TableField("SOURCE_ESTIMATED_TOKENS")
    private Integer sourceEstimatedTokens;
    @TableField("SUMMARY_PROMPT_VERSION")
    private String promptVersion;
    @TableField("SUMMARY_MAX_OUTPUT_TOKENS")
    private Integer summaryMaxOutputTokens;

    public AgentContextSnapshotEntity() {
    }

    public String getSnapshotId() { return snapshotId; }
    public void setSnapshotId(String snapshotId) { this.snapshotId = snapshotId; }
    public String getThreadId() { return threadId; }
    public void setThreadId(String threadId) { this.threadId = threadId; }
    public Long getThroughSequence() { return throughSequence; }
    public void setThroughSequence(Long throughSequence) { this.throughSequence = throughSequence; }
    public Long getVersionNo() { return versionNo; }
    public void setVersionNo(Long versionNo) { this.versionNo = versionNo; }
    public Integer getEstimatedTokens() { return estimatedTokens; }
    public void setEstimatedTokens(Integer estimatedTokens) { this.estimatedTokens = estimatedTokens; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Integer getFormatVersion() { return formatVersion; }
    public void setFormatVersion(Integer formatVersion) { this.formatVersion = formatVersion; }
    public String getBaseSnapshotId() { return baseSnapshotId; }
    public void setBaseSnapshotId(String baseSnapshotId) { this.baseSnapshotId = baseSnapshotId; }
    public Long getSourceFromSequence() { return sourceFromSequence; }
    public void setSourceFromSequence(Long sourceFromSequence) { this.sourceFromSequence = sourceFromSequence; }
    public Integer getSourceEstimatedTokens() { return sourceEstimatedTokens; }
    public void setSourceEstimatedTokens(Integer sourceEstimatedTokens) { this.sourceEstimatedTokens = sourceEstimatedTokens; }
    public String getPromptVersion() { return promptVersion; }
    public void setPromptVersion(String promptVersion) { this.promptVersion = promptVersion; }
    public Integer getSummaryMaxOutputTokens() { return summaryMaxOutputTokens; }
    public void setSummaryMaxOutputTokens(Integer summaryMaxOutputTokens) { this.summaryMaxOutputTokens = summaryMaxOutputTokens; }
}
