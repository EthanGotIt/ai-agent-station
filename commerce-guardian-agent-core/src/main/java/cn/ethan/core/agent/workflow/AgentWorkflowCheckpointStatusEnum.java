package cn.ethan.core.agent.workflow;

/**
 * 类型职责：限制固定 Workflow 人工执行确认的状态。
 *
 * @author ethan
 * @date 2026-08-27
 */
public enum AgentWorkflowCheckpointStatusEnum {
    OPEN,
    APPROVED,
    REJECTED,
    SUPERSEDED
}
