package cn.ethan.core.agent.workflow;


import java.util.List;
import java.util.Optional;

/**
 * 类型职责：定义 WorkflowTask 的本地事务持久化边界。
 *
 * @author ethan
 * @date 2026-08-19
 */
public interface AgentWorkflowTaskStore {

    void create(AgentWorkflowTaskModel run);

    Optional<AgentWorkflowTaskModel> find(String userId, String taskId);

    /**
     * 在本地 Workflow 事务内锁定并读取 Run；非数据库实现默认退化为普通读取。
     * 业务动作提交前必须使用该入口复核编排版本和乐观版本。
     */
    default Optional<AgentWorkflowTaskModel> findForUpdate(String userId, String taskId) {
        return find(userId, taskId);
    }

    /** 按来源 Turn 查找已有 Run，用于重复启动去重和来源参数冲突校验。 */
    default Optional<AgentWorkflowTaskModel> findBySource(
            String userId, String turnId, AgentWorkflowTypeEnum workflowType
    ) {
        return Optional.empty();
    }

    /** 为新 Agent Turn 注入最近的持久化业务状态；不依赖压缩摘要的时效。 */
    default List<AgentWorkflowTaskModel> findRecent(String userId, String threadId, int limit) {
        return List.of();
    }

    void update(AgentWorkflowTaskModel run);
}
