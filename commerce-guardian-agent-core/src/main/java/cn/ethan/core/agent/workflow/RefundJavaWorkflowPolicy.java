package cn.ethan.core.agent.workflow;

import cn.ethan.core.agent.action.ExternalActionTypeEnum;
import cn.ethan.core.commerce.order.OrderSnapshotModel;
import cn.ethan.core.commerce.order.OrderStatusEnum;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 类型职责：沿用既有退款资格，并把原因、实付金额和币种绑定到授权指纹。
 *
 * @author ethan
 * @date 2026-09-28
 */
public final class RefundJavaWorkflowPolicy implements OrderWriteJavaWorkflowPolicy {

    @Override public String intent() { return "REFUND"; }

    @Override public String label() { return "退款"; }

    @Override public String impact(OrderSnapshotModel order) {
        return "将对订单 " + order.orderId() + " 提交全额退款，金额 "
                + (order.paidAmount() == null ? "待订单服务确认" : order.paidAmount().toPlainString())
                + " " + (order.currency() == null ? "" : order.currency());
    }

    @Override public ExternalActionTypeEnum actionType() { return ExternalActionTypeEnum.REFUND; }

    @Override public AgentWorkflowOrchestrationVersionEnum version() {
        return AgentWorkflowOrchestrationVersionEnum.REFUND_JAVA_V1;
    }

    @Override public boolean needsReason() { return true; }

    @Override public boolean eligible(OrderSnapshotModel order, String userId) {
        return order != null && userId != null && userId.equals(order.userId())
                && List.of(OrderStatusEnum.PAID, OrderStatusEnum.SHIPPED,
                        OrderStatusEnum.DELIVERED, OrderStatusEnum.REFUNDED).contains(order.status());
    }

    @Override public List<String> authorizationValues(OrderSnapshotModel order, String reason) {
        return List.of(intent(), order.userId(), order.orderId(), order.status().name(),
                reason == null ? "" : reason.strip(),
                order.paidAmount() == null ? "" : order.paidAmount().toPlainString(),
                order.currency() == null ? "" : order.currency());
    }

    @Override public Map<String, String> commandPayload(OrderSnapshotModel order, String reason) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("orderId", order.orderId());
        payload.put("reason", reason.strip());
        if (order.paidAmount() != null) payload.put("paidAmount", order.paidAmount().toPlainString());
        if (order.currency() != null) payload.put("currency", order.currency());
        return Map.copyOf(payload);
    }
}
