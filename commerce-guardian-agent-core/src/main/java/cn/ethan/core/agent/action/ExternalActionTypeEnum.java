package cn.ethan.core.agent.action;

/**
 * 类型职责：限制可由 Agent Workflow 创建的外部写操作类型。
 *
 * @author ethan
 * @date 2026-08-19
 */
public enum ExternalActionTypeEnum {
    REFUND,
    EXPEDITE,
    DELETE_ORDER,
    /** 历史兼容值；新生产 Workflow 不再创建隐藏/恢复命令。 */
    HIDE_ORDER,
    RESTORE_ORDER
}
