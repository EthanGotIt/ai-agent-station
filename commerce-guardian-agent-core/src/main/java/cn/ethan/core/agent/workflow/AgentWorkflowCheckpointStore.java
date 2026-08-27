package cn.ethan.core.agent.workflow;

import java.util.Optional;

/**
 * 类型职责：以版本和事实指纹 CAS 管理固定 Workflow 的人工执行确认。
 *
 * @author ethan
 * @date 2026-08-27
 */
public interface AgentWorkflowCheckpointStore {

    Optional<AgentWorkflowCheckpointModel> find(String userId, String checkpointId);

    Optional<AgentWorkflowCheckpointModel> findOpen(String userId, String threadId);

    void create(AgentWorkflowCheckpointModel checkpoint);

    boolean decide(String userId, String checkpointId, long expectedVersion,
                   AgentWorkflowDecisionEnum decision, String currentFactsFingerprint);

    boolean supersede(String userId, String checkpointId, long expectedVersion);
}
