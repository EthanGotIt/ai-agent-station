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
    HIDE_ORDER,
    RESTORE_ORDER;

    public boolean readOnly() {
        return this == QUERY_LOGISTICS || this == REFRESH_ORDER;
    }

    public String workflowIntent() {
        return switch (this) {
            case REFUND, EXPEDITE, HIDE_ORDER, RESTORE_ORDER -> name();
            case QUERY_LOGISTICS, REFRESH_ORDER -> throw new IllegalStateException(
                    "只读订单动作没有 Workflow intent");
        };
    }
}
