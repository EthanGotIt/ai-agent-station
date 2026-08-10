package cn.ethan.core.workflow.enums;

/**
 * Workflow 运行状态枚举：描述可持久化流程实例的业务生命周期。
 *
 * @author ethan
 * @date 2026-08-07
 */
public enum WorkflowRunStatusEnum {
    RUNNING,
    WAITING_USER_INPUT,
    COMPLETED,
    REJECTED,
    FAILED,
    CANCELLED
}
