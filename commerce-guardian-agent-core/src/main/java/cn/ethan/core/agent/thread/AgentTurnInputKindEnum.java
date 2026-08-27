package cn.ethan.core.agent.thread;

/**
 * Turn 输入的持久化判别类型，用于恢复时选择确定性执行路径。
 *
 * @author ethan
 * @date 2026-08-24
 */
public enum AgentTurnInputKindEnum {
    MESSAGE,
    QUESTION_ANSWER,
    WORKFLOW_DECISION,
    /** 旧版本结构化 Workflow 回答，仅用于迁移兼容。 */
    WORKFLOW_ANSWER,
    ORDER_ACTION,
    AGENT_CONTINUATION
}
