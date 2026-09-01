package cn.ethan.core.agent.workflow;

/**
 * 类型职责：限制可由协调 Agent 启动的确定性 Workflow 类型。
 *
 * @author ethan
 * @date 2026-08-20
 */
public enum AgentWorkflowTypeEnum {
    ORDER_SERVICE,
    REFUND,
    EXPEDITE
}
