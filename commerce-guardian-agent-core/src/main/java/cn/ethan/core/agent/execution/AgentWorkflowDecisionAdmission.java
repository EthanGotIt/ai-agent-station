package cn.ethan.core.agent.execution;

/**
 * 类型职责：以本地事务原子完成 Workflow Checkpoint 决策 Turn 和决策事实。
 *
 * @author ethan
 * @date 2026-08-27
 */
public interface AgentWorkflowDecisionAdmission {

    AgentWorkflowDecisionAdmissionResult admit(AgentWorkflowDecisionAdmissionCommand command);
}
