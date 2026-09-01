package cn.ethan.infrastructure.agent.workflow.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * 类型职责：映射 LangGraph 技术快照，不承载业务授权事实。
 *
 * @author ethan
 * @date 2026-08-27
 */
@TableName("AGENT_GRAPH_SNAPSHOT")
public final class AgentGraphSnapshotEntity {

    @TableId(value = "SNAPSHOT_ID", type = IdType.INPUT)
    private String snapshotId;
    @TableField("RUN_ID")
    private String runId;
    @TableField("GRAPH_THREAD_ID")
    private String graphThreadId;
    @TableField("CHECKPOINT_ID")
    private String checkpointId;
    @TableField("NODE_ID")
    private String nodeId;
    @TableField("NEXT_NODE_ID")
    private String nextNodeId;
    @TableField("STATE_JSON")
    private String stateJson;
    @TableField("WORKFLOW_VERSION")
    private Long workflowVersion;
    @TableField("FACTS_FINGERPRINT")
    private String factsFingerprint;
    @TableField("CREATED_AT")
    private Instant createdAt;
    @TableField("UPDATED_AT")
    private Instant updatedAt;

    public String getSnapshotId() { return snapshotId; }
    public void setSnapshotId(String snapshotId) { this.snapshotId = snapshotId; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getGraphThreadId() { return graphThreadId; }
    public void setGraphThreadId(String graphThreadId) { this.graphThreadId = graphThreadId; }
    public String getCheckpointId() { return checkpointId; }
    public void setCheckpointId(String checkpointId) { this.checkpointId = checkpointId; }
    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }
    public String getNextNodeId() { return nextNodeId; }
    public void setNextNodeId(String nextNodeId) { this.nextNodeId = nextNodeId; }
    public String getStateJson() { return stateJson; }
    public void setStateJson(String stateJson) { this.stateJson = stateJson; }
    public Long getWorkflowVersion() { return workflowVersion; }
    public void setWorkflowVersion(Long workflowVersion) { this.workflowVersion = workflowVersion; }
    public String getFactsFingerprint() { return factsFingerprint; }
    public void setFactsFingerprint(String factsFingerprint) { this.factsFingerprint = factsFingerprint; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
