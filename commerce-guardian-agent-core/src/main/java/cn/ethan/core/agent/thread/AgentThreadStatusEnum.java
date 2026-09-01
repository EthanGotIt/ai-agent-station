package cn.ethan.core.agent.thread;

/**
 * Agent Thread 生命周期。
 *
 * @author ethan
 * @date 2026-08-19
 */
public enum AgentThreadStatusEnum {
    ACTIVE,
    /** 历史兼容状态：仅允许读取，不再提供归档或恢复写操作。 */
    ARCHIVED
}
