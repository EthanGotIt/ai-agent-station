package cn.ethan.core.agent.enums;

/**
 * Agent 状态枚举：定义对外接口允许返回的终态。
 *
 * @author ethan
 * @date 2026-08-05
 */
public enum AgentStatusEnum {
    COMPLETED,
    WAITING_USER_INPUT,
    CANCELLED,
    FAILED
}
