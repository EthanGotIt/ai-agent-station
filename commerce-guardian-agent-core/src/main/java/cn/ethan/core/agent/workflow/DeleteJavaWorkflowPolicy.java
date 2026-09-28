package cn.ethan.core.agent.workflow;

import cn.ethan.core.agent.action.ExternalActionTypeEnum;
import cn.ethan.core.commerce.order.OrderSnapshotModel;

import java.util.List;
import java.util.Map;

/**
 * 类型职责：沿用订单可见且归属正确即可删除的规则，并明确不可逆的删除范围。
 *
 * @author ethan
 * @date 2026-09-28
 */
public final class DeleteJavaWorkflowPolicy implements OrderWriteJavaWorkflowPolicy {

    private static final String SCOPE = "ORDER_AND_LOGISTICS";

    @Override public String intent() { return "DELETE_ORDER"; }

    @Override public String label() { return "删除订单记录"; }

    @Override public String impact(OrderSnapshotModel order) {
        return "将永久删除订单 " + order.orderId() + " 的记录及可删除的物流轨迹，删除后不可恢复";
    }

    @Override public ExternalActionTypeEnum actionType() { return ExternalActionTypeEnum.DELETE_ORDER; }

    @Override public AgentWorkflowOrchestrationVersionEnum version() {
        return AgentWorkflowOrchestrationVersionEnum.DELETE_JAVA_V1;
    }

    @Override public boolean eligible(OrderSnapshotModel order, String userId) {
        return order != null && userId != null && userId.equals(order.userId());
    }

    @Override public List<String> authorizationValues(OrderSnapshotModel order, String reason) {
        return List.of(intent(), order.userId(), order.orderId(), SCOPE,
                order.status().name(), order.hiddenAt() == null ? "ACTIVE" : "HIDDEN");
    }

    @Override public Map<String, String> commandPayload(OrderSnapshotModel order, String reason) {
        return Map.of("orderId", order.orderId(), "scope", SCOPE);
    }
}
