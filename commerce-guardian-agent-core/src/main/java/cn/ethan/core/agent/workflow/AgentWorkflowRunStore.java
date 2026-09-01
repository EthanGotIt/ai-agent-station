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

    void update(AgentWorkflowRunModel run);
}
