package cn.ethan.core.agent.thread;

import java.util.List;

/**
 * 类型职责：表达订单查询返回的候选列表及其事实状态。
 *
 * @author ethan
 * @date 2026-09-13
 */
public record AgentOrderListPayloadModel(
        String status,
        List<AgentOrderSnapshotPayloadModel> orders
) implements AgentItemPayloadValue {

    public AgentOrderListPayloadModel {
        status = required(status, "status");
        orders = orders == null ? List.of() : List.copyOf(orders);
    }

    @Override
    public AgentItemTypeEnum type() {
        return AgentItemTypeEnum.ORDER_LIST;
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value.trim();
    }
}
