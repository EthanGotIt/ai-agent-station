package cn.ethan.core.agent.workflow;

/**
 * 类型职责：表达 WorkflowRun 的持久化状态机，禁止以任意字符串绕过边界。
 *
 * @author ethan
 * @date 2026-08-20
 */
public enum AgentWorkflowStatusEnum {
    WAITING_USER_INPUT,
    WAITING_EXTERNAL_ACTION,
    COMPLETED,
    REJECTED,
    FAILED,
    MANUAL_RETRY_REQUIRED
}
