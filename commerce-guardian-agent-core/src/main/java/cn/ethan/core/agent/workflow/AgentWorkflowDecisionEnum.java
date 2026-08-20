package cn.ethan.core.agent.workflow;

/**
 * 类型职责：限制 QuestionCard 的授权决定，未知值一律按拒绝处理。
 *
 * @author ethan
 * @date 2026-08-20
 */
public enum AgentWorkflowDecisionEnum {
    APPROVE,
    REJECT
}
