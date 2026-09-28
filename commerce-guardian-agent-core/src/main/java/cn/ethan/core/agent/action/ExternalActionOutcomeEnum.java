package cn.ethan.core.agent.action;

/**
 * 类型职责：区分外部动作的业务结果是否已知，与命令的调度状态分离。
 *
 * @author ethan
 * @date 2026-09-24
 */
public enum ExternalActionOutcomeEnum {
    PENDING,
    SUCCEEDED,
    FAILED,
    UNKNOWN
}
