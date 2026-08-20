package cn.ethan.core.agent.action;

/**
 * 类型职责：描述外部动作命令的可恢复生命周期。
 *
 * @author ethan
 * @date 2026-08-19
 */
public enum ExternalActionStatusEnum {
    PENDING,
    PROCESSING,
    RETRY_WAIT,
    MANUAL_RETRY_REQUIRED,
    SUCCEEDED
}
