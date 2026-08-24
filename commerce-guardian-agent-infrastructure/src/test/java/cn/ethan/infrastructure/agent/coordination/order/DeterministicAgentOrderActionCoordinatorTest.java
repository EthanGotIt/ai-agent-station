package cn.ethan.infrastructure.agent.coordination.order;

import cn.ethan.core.agent.coordination.AgentOrderActionInput;
import cn.ethan.core.agent.coordination.AgentOrderActionTypeEnum;
import cn.ethan.core.agent.coordination.AgentTurnCoordinator;
import cn.ethan.core.agent.execution.AgentExecutionContext;
import cn.ethan.core.agent.thread.AgentThreadModel;
import cn.ethan.core.agent.thread.AgentThreadStatusEnum;
import cn.ethan.core.agent.thread.AgentTurnModel;
import cn.ethan.core.agent.thread.AgentTurnStatusEnum;
import cn.ethan.core.agent.workflow.AgentWorkflowEngine;
import cn.ethan.core.commerce.order.LogisticsEventModel;
import cn.ethan.core.commerce.order.LogisticsGateway;
import cn.ethan.core.commerce.order.OrderGateway;
import cn.ethan.core.commerce.order.OrderLookupResultModel;
import cn.ethan.core.commerce.order.OrderSnapshotModel;
import cn.ethan.core.commerce.order.OrderStatusEnum;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 类型职责：验证订单卡片动作不调用模型，写动作确认前也不创建外部命令。
 *
 * @author ethan
 * @date 2026-08-24
 */
class DeterministicAgentOrderActionCoordinatorTest {

    private static final Instant NOW = Instant.parse("2026-08-24T00:00:00Z");

    @Test
    void queryProducesStructuredFactsOnly() {
        AtomicReference<String> lookedUp = new AtomicReference<>();
        AtomicReference<String> traced = new AtomicReference<>();
        OrderSnapshotModel order = order();
        OrderGateway orders = (orderId, userId) -> {
            lookedUp.set(orderId + ":" + userId);
            return OrderLookupResultModel.found(order);
        };
        LogisticsGateway logistics = (orderId, userId) -> {
            traced.set(orderId + ":" + userId);
            return List.of(new LogisticsEventModel("event-1", orderId, "IN_TRANSIT", "上海", "已发出", NOW));
        };
        AtomicReference<String> workflowCall = new AtomicReference<>();
        DeterministicAgentOrderActionCoordinator coordinator = new DeterministicAgentOrderActionCoordinator(
                orders, logistics, workflow(workflowCall));

        AgentTurnCoordinator.AgentCoordinatorResult result = coordinator.run(
                thread(), turn(), List.of(),
                new AgentOrderActionInput("source-turn", "order-1", AgentOrderActionTypeEnum.QUERY_LOGISTICS),
                executionContext());

        assertEquals("order-1:user-1", lookedUp.get());
        assertEquals("order-1:user-1", traced.get());
        assertTrue(workflowCall.get() == null);
        assertEquals(List.of("ORDER_DETAIL", "LOGISTICS_TIMELINE"),
                result.items().stream().map(AgentTurnCoordinator.AgentItemDraft::type).toList());
        assertTrue(result.items().get(0).payload().contains("order-1"));
    }

    @Test
    void writeStartsWorkflowWithoutExternalCommand() {
        AtomicReference<String> workflowCall = new AtomicReference<>();
        DeterministicAgentOrderActionCoordinator coordinator = new DeterministicAgentOrderActionCoordinator(
                (orderId, userId) -> OrderLookupResultModel.found(order()),
                (orderId, userId) -> List.of(), workflow(workflowCall));

        AgentTurnCoordinator.AgentCoordinatorResult result = coordinator.run(
                thread(), turn(), List.of(),
                new AgentOrderActionInput("source-turn", "order-1", AgentOrderActionTypeEnum.REFUND),
                executionContext());

        assertEquals("ORDER_SERVICE:REFUND:order-1", workflowCall.get());
        assertEquals("run-1", result.workflowRunId());
        assertTrue(result.items().isEmpty());
    }

    private AgentWorkflowEngine workflow(AtomicReference<String> called) {
        return new AgentWorkflowEngine() {
            @Override
            public StartResult start(AgentThreadModel thread, AgentTurnModel turn,
                                     String operation, Map<String, String> arguments) {
                called.set(operation + ":" + arguments.get("intent") + ":" + arguments.get("orderId"));
                return new StartResult("run-1", null);
            }

            @Override
            public ResumeResult resume(AgentThreadModel thread, AgentTurnModel turn,
                                       Map<String, String> answers) {
                return new ResumeResult("", "", null);
            }
        };
    }

    private AgentExecutionContext executionContext() {
        return new AgentExecutionContext(Clock.fixed(NOW, ZoneOffset.UTC), NOW.plusSeconds(30));
    }

    private AgentThreadModel thread() {
        return new AgentThreadModel("thread-1", "user-1", "订单 Thread", AgentThreadStatusEnum.ACTIVE,
                null, null, 0, NOW, NOW);
    }

    private AgentTurnModel turn() {
        return new AgentTurnModel("turn-1", "thread-1", "user-1", "request-1", "订单动作",
                AgentTurnStatusEnum.ACTIVE, 1, null, null, NOW, NOW, null);
    }

    private static OrderSnapshotModel order() {
        return new OrderSnapshotModel("order-1", "user-1", OrderStatusEnum.SHIPPED, 0,
                NOW, NOW.plusSeconds(3_600), NOW, "IN_TRANSIT");
    }
}
