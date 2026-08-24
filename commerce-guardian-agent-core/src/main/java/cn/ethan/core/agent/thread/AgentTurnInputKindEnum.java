package cn.ethan.core.agent.thread;

/**
 * Turn 输入的持久化判别类型，用于恢复时选择确定性执行路径。
 *
 * @author ethan
 * @date 2026-08-24
 */
public enum AgentTurnInputKindEnum {
    MESSAGE,
    WORKFLOW_ANSWER,
    ORDER_ACTION
}
