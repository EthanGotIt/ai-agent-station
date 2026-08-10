package cn.ethan.core.workflow.enums;

/**
 * Workflow 节点结果类型枚举：定义节点可返回的控制信号。
 *
 * @author ethan
 * @date 2026-08-05
 */
public enum NodeResultTypeEnum {
    CONTINUE,
    RETRY,
    WAITING_USER_INPUT,
    COMPLETE,
    FAILED
}
