package cn.ethan.core.agent.enums;

/**
 * 请求生命周期状态枚举：描述请求从准备到终止的状态变化。
 *
 * @author ethan
 * @date 2026-08-05
 */
public enum RequestLifecycleStateEnum {
    PREPARED,
    QUEUED,
    ACTIVE,
    COMPLETED,
    FAILED,
    CANCELLING,
    CANCELLED
}
