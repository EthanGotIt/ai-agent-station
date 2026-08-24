package cn.ethan.infrastructure.agent.coordination.order;

import cn.ethan.core.agent.coordination.AgentOrderActionCoordinator;
import cn.ethan.core.agent.coordination.AgentOrderActionInput;
import cn.ethan.core.agent.coordination.AgentOrderActionTypeEnum;
import cn.ethan.core.agent.coordination.AgentTurnCoordinator;
import cn.ethan.core.agent.execution.AgentExecutionContext;
import cn.ethan.core.agent.thread.AgentItemModel;
import cn.ethan.core.agent.thread.AgentThreadModel;
import cn.ethan.core.agent.thread.AgentTurnModel;
import cn.ethan.core.agent.workflow.AgentWorkflowEngine;
import cn.ethan.core.commerce.order.LogisticsEventModel;
import cn.ethan.core.commerce.order.LogisticsGateway;
import cn.ethan.core.commerce.order.OrderGateway;
import cn.ethan.core.commerce.order.OrderLookupResultModel;
import cn.ethan.core.commerce.order.OrderLookupStatusEnum;
import cn.ethan.core.commerce.order.OrderSnapshotModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 类型职责：执行订单卡片的确定性动作，不经过模型，也不把写操作提前变成外部命令。
 *
 * @author ethan
 * @date 2026-08-24
 */
@Component
public final class DeterministicAgentOrderActionCoordinator implements AgentOrderActionCoordinator {

    private final OrderGateway orders;
    private final LogisticsGateway logistics;
    private final AgentWorkflowEngine workflowEngine;

    public DeterministicAgentOrderActionCoordinator(
            OrderGateway orders,
            LogisticsGateway logistics,
            AgentWorkflowEngine workflowEngine
    ) {
        this.orders = orders;
        this.logistics = logistics;
        this.workflowEngine = workflowEngine;
    }

    @Override
    public AgentTurnCoordinator.AgentCoordinatorResult run(
            AgentThreadModel thread,
            AgentTurnModel turn,
            List<AgentItemModel> context,
            AgentOrderActionInput input,
            AgentExecutionContext executionContext
    ) {
        executionContext.checkActive();
        if (!input.actionType().readOnly()) {
            AgentWorkflowEngine.StartResult started = workflowEngine.start(
                    thread, turn, "ORDER_SERVICE",
                    Map.of("intent", input.actionType().workflowIntent(), "orderId", input.orderId()));
            executionContext.checkActive();
            return new AgentTurnCoordinator.AgentCoordinatorResult(
                    "", List.of(), started.question(), started.runId(), true);
        }
        OrderLookupResultModel lookup = orders.findOrder(input.orderId(), thread.userId());
        executionContext.checkActive();
        if (lookup == null || lookup.status() != OrderLookupStatusEnum.FOUND || lookup.order() == null) {
            return new AgentTurnCoordinator.AgentCoordinatorResult(
                    "", List.of(new AgentTurnCoordinator.AgentItemDraft(
                    "ERROR", errorPayload(errorCode(lookup), "订单事实暂不可用，请稍后重试。"))),
                    null, null, false);
        }
        OrderSnapshotModel order = lookup.order();
        List<AgentTurnCoordinator.AgentItemDraft> facts = new ArrayList<>();
        if (input.actionType() == AgentOrderActionTypeEnum.REFRESH_ORDER
                || input.actionType() == AgentOrderActionTypeEnum.QUERY_LOGISTICS) {
            facts.add(new AgentTurnCoordinator.AgentItemDraft("ORDER_DETAIL", orderPayload(order)));
        }
        if (input.actionType() == AgentOrderActionTypeEnum.QUERY_LOGISTICS) {
            List<LogisticsEventModel> trace = logistics.findTrace(order.orderId(), thread.userId());
            executionContext.checkActive();
            facts.add(new AgentTurnCoordinator.AgentItemDraft(
                    "LOGISTICS_TIMELINE", logisticsPayload(order.orderId(), trace)));
        }
        return new AgentTurnCoordinator.AgentCoordinatorResult("", facts, null, null, false);
    }

    private String errorCode(OrderLookupResultModel lookup) {
        return lookup == null || lookup.status() == null ? "ORDER_LOOKUP_FAILED" : switch (lookup.status()) {
            case NOT_FOUND -> "ORDER_NOT_FOUND";
            case ACCESS_DENIED -> "ORDER_ACCESS_DENIED";
            case TEMPORARY_FAILURE -> "ORDER_LOOKUP_TEMPORARY_FAILURE";
            case FOUND -> "ORDER_FACT_INVALID";
        };
    }

    private String orderPayload(OrderSnapshotModel order) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", order.orderId());
        payload.put("userId", order.userId());
        payload.put("orderStatus", order.status().name());
        payload.put("daysSinceDelivery", order.daysSinceDelivery());
        payload.put("createdAt", string(order.createdAt()));
        payload.put("expectedDeliveryAt", string(order.expectedDeliveryAt()));
        payload.put("lastLogisticsAt", string(order.lastLogisticsAt()));
        payload.put("logisticsStatus", order.logisticsStatus());
        payload.put("paidAmount", order.paidAmount());
        payload.put("currency", order.currency());
        payload.put("itemSummary", order.itemSummary());
        payload.put("visibility", order.hiddenAt() == null ? "ACTIVE" : "HIDDEN");
        return json(payload);
    }

    private String logisticsPayload(String orderId, List<LogisticsEventModel> trace) {
        List<Map<String, Object>> events = new ArrayList<>();
        if (trace != null) {
            for (LogisticsEventModel event : trace) {
                if (event == null) {
                    continue;
                }
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("eventId", event.eventId());
                value.put("orderId", event.orderId());
                value.put("status", event.status());
                value.put("location", event.location());
                value.put("description", event.description());
                value.put("occurredAt", string(event.occurredAt()));
                events.add(value);
            }
        }
        return json(Map.of("orderId", orderId, "events", events));
    }

    private String errorPayload(String code, String message) {
        return json(Map.of("code", code, "message", message));
    }

    private String string(java.time.Instant value) {
        return value == null ? null : value.toString();
    }

    private String json(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder result = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) result.append(',');
                result.append('"').append(escape(String.valueOf(entry.getKey()))).append("\":")
                        .append(json(entry.getValue()));
                first = false;
            }
            return result.append('}').toString();
        }
        if (value instanceof List<?> list) {
            StringBuilder result = new StringBuilder("[");
            boolean first = true;
            for (Object item : list) {
                if (!first) result.append(',');
                result.append(json(item));
                first = false;
            }
            return result.append(']').toString();
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return "\"" + escape(String.valueOf(value)) + "\"";
    }

    private String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }
}
