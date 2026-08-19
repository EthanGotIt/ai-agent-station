package cn.ethan.core.agent.thread.enums;

/**
 * Agent Turn 执行状态。
 *
 * @author ethan
 * @date 2026-08-19
 */
public enum AgentTurnStatusEnum {
    QUEUED,
    ACTIVE,
    WAITING_USER_INPUT,
    WAITING_EXTERNAL_ACTION,
    COMPLETED,
    CANCELLED,
    TIMED_OUT,
    FAILED
}
