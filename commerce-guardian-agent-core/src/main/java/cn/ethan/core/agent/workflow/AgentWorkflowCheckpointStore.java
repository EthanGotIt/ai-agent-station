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

    /**
     * 在本地 Workflow 事务内锁定并读取 Checkpoint；内存测试实现可复用普通读取。
     */
    default Optional<AgentWorkflowCheckpointModel> findForUpdate(String userId, String checkpointId) {
        return find(userId, checkpointId);
    }

    Optional<AgentWorkflowCheckpointModel> findOpen(String userId, String threadId);

    /** 按 Run 读取开放确认，供重复启动恢复原交互。 */
    default Optional<AgentWorkflowCheckpointModel> findOpenByRun(String userId, String runId) {
        return Optional.empty();
    }

    void create(AgentWorkflowCheckpointModel checkpoint);

    boolean decide(String userId, String checkpointId, long expectedVersion,
                   AgentWorkflowDecisionEnum decision, String currentFactsFingerprint);

    /**
     * 使事实已变化的 Checkpoint 失效。除了尚未决策的卡片，也允许收口一个已经批准
     * 但尚未创建外部动作命令的卡片，避免批准快照在恢复时继续生效。
     */
    boolean supersede(String userId, String checkpointId, long expectedVersion);
}
