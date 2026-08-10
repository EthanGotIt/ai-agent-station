package cn.ethan.core.workflow.enums;

/**
 * Workflow 状态枚举：定义流程执行完成、等待输入和失败状态。
 *
 * @author ethan
 * @date 2026-08-05
 */
public enum WorkflowStatusEnum {
    COMPLETED,
    WAITING_USER_INPUT,
    FAILED
}
