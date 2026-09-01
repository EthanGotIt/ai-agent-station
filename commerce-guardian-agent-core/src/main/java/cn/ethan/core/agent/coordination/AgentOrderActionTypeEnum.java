package cn.ethan.core.agent.coordination;

/**
 * 订单卡片可直接发起的确定性动作；写操作仍由现有 Workflow 负责最终授权。
 *
 * @author ethan
 * @date 2026-08-24
 */
public enum AgentOrderActionTypeEnum {
    QUERY_LOGISTICS,
    REFRESH_ORDER,
    REFUND,
    EXPEDITE,
    DELETE_ORDER,
    /** 历史兼容值；新生产入口不再创建隐藏/恢复动作。 */
    HIDE_ORDER,
    RESTORE_ORDER;

    public boolean readOnly() {
        return this == QUERY_LOGISTICS || this == REFRESH_ORDER;
    }

    public String workflowIntent() {
        return switch (this) {
            case REFUND, EXPEDITE, DELETE_ORDER -> name();
            case HIDE_ORDER, RESTORE_ORDER -> throw new IllegalStateException(
                    "订单隐藏/恢复动作已移除，请使用 DELETE_ORDER");
            case QUERY_LOGISTICS, REFRESH_ORDER -> throw new IllegalStateException(
                    "只读订单动作没有 Workflow intent");
        };
    }

    /** 新生产入口允许的写动作；旧隐藏/恢复值仅用于读取历史 Item。 */
    public boolean removed() {
        return this == HIDE_ORDER || this == RESTORE_ORDER;
    }
}
