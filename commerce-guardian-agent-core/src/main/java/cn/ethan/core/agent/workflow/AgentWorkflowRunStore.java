package cn.ethan.core.agent.workflow;


import java.util.Optional;

/**
 * 类型职责：定义 WorkflowRun 的本地事务持久化边界。
 *
 * @author ethan
 * @date 2026-08-19
 */
public interface AgentWorkflowRunStore {

    void create(AgentWorkflowRunModel run);

    Optional<AgentWorkflowRunModel> find(String userId, String runId);

    /**
     * 在本地 Workflow 事务内锁定并读取 Run；非数据库实现默认退化为普通读取。
     * 业务动作提交前必须使用该入口复核编排版本和乐观版本。
     */
    default Optional<AgentWorkflowRunModel> findForUpdate(String userId, String runId) {
        return find(userId, runId);
    }

    /** 按来源 Turn 查找已有 Run，用于重复启动去重和来源参数冲突校验。 */
    default Optional<AgentWorkflowRunModel> findBySource(
            String userId, String turnId, AgentWorkflowTypeEnum workflowType
    ) {
        return Optional.empty();
    }

    void update(AgentWorkflowRunModel run);
}
