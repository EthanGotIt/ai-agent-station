package cn.ethan.infrastructure.agent.coordination.springai;

import cn.ethan.core.agent.execution.AgentRuntimeMetrics;
import cn.ethan.core.agent.thread.AgentThreadModel;
import cn.ethan.core.agent.thread.AgentTurnModel;
import cn.ethan.core.agent.workflow.AgentWorkflowEngine;
import cn.ethan.core.commerce.order.OrderLookupResultModel;
import cn.ethan.core.commerce.order.OrderSnapshotModel;
import cn.ethan.core.commerce.order.OrderStatusEnum;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tool 边界测试：验证调用关联和参数拒绝在实际 Tool wrapper 内完成。
 *
 * @author ethan
 * @date 2026-08-21
 */
class SpringAiAgentToolBoundaryTest {

    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");

    @Test
    void repeatedSameToolCallsHaveDistinctCorrelatedInvocationIds() throws Exception {
        AtomicInteger lookupCalls = new AtomicInteger();
        SpringAiAgentTurnCoordinator.WorkflowInvocation invocation = invocation();
        SpringAiAgentTurnCoordinator.ReadOnlyTools tools = new SpringAiAgentTurnCoordinator.ReadOnlyTools(
                "user-1",
                (orderId, userId) -> {
                    lookupCalls.incrementAndGet();
                    return OrderLookupResultModel.notFound();
                },
                (orderId, userId) -> List.of(),
                invocation
        );

        tools.lookupOrder(" ORDER-1 ");
        tools.lookupOrder("ORDER-2");

        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode firstCall = objectMapper.readTree(invocation.traces().get(0).payload());
        JsonNode firstResult = objectMapper.readTree(invocation.traces().get(1).payload());
        JsonNode secondCall = objectMapper.readTree(invocation.traces().get(2).payload());
        JsonNode secondResult = objectMapper.readTree(invocation.traces().get(3).payload());

        assertEquals(2, lookupCalls.get());
        assertEquals(4, invocation.traces().size());
        assertNotEquals(firstCall.path("invocationId").asString(), secondCall.path("invocationId").asString());
        assertEquals(firstCall.path("invocationId").asString(), firstResult.path("invocationId").asString());
        assertEquals(secondCall.path("invocationId").asString(), secondResult.path("invocationId").asString());
        assertEquals("ORDER-1", firstCall.path("arguments").path("orderId").asString());
        assertEquals("SUCCESS", secondResult.path("status").asString());
    }

    @Test
    void blankReadOnlyArgumentIsRecordedAndDoesNotReachGateway() {
        AtomicInteger lookupCalls = new AtomicInteger();
        SpringAiAgentTurnCoordinator.WorkflowInvocation invocation = invocation();
        SpringAiAgentTurnCoordinator.ReadOnlyTools tools = new SpringAiAgentTurnCoordinator.ReadOnlyTools(
                "user-1",
                (orderId, userId) -> {
                    lookupCalls.incrementAndGet();
                    return OrderLookupResultModel.notFound();
                },
                (orderId, userId) -> List.of(),
                invocation
        );

        assertThrows(IllegalArgumentException.class, () -> tools.lookupOrder("  "));

        assertEquals(0, lookupCalls.get());
        assertEquals(2, invocation.traces().size());
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode call = objectMapper.readTree(invocation.traces().get(0).payload());
        JsonNode result = objectMapper.readTree(invocation.traces().get(1).payload());
        assertEquals(call.path("invocationId").asString(), result.path("invocationId").asString());
        assertEquals("FAILED", result.path("status").asString());
    }

    @Test
    void blankRefundReasonIsRejectedBeforeWorkflowEngine() {
        AtomicBoolean engineCalled = new AtomicBoolean();
        SpringAiAgentTurnCoordinator.WorkflowInvocation invocation = invocation();
        AgentWorkflowEngine engine = new AgentWorkflowEngine() {
            @Override
            public StartResult start(
                    AgentThreadModel thread, AgentTurnModel turn, String operation, Map<String, String> arguments
            ) {
                engineCalled.set(true);
                throw new AssertionError("invalid Tool arguments must not reach the Workflow engine");
            }

            @Override
            public ResumeResult resume(AgentThreadModel thread, AgentTurnModel turn, Map<String, String> answers) {
                throw new UnsupportedOperationException();
            }
        };
        SpringAiAgentTurnCoordinator.WorkflowTools tools = new SpringAiAgentTurnCoordinator.WorkflowTools(
                null, null, engine, invocation);

        assertThrows(IllegalArgumentException.class, () -> tools.startRefund("ORDER-1", " "));

        assertFalse(engineCalled.get());
        assertEquals(2, invocation.traces().size());
    }

    @Test
    void orderToolProjectsOnlyModelSafeFields() throws Exception {
        SpringAiAgentTurnCoordinator.WorkflowInvocation invocation = invocation();
        SpringAiAgentTurnCoordinator.ReadOnlyTools tools = new SpringAiAgentTurnCoordinator.ReadOnlyTools(
                "user-1",
                (orderId, userId) -> OrderLookupResultModel.found(new OrderSnapshotModel(
                        orderId, "internal-user-1", OrderStatusEnum.PAID, 2, NOW, NOW.plusSeconds(60),
                        NOW.plusSeconds(30), "IN_TRANSIT", new BigDecimal("19.90"), "CNY")),
                (orderId, userId) -> List.of(),
                invocation
        );

        String result = tools.lookupOrder("ORDER-1");

        JsonNode safe = new ObjectMapper().readTree(result);
        assertEquals("FOUND", safe.path("status").asString());
        assertEquals("ORDER-1", safe.path("orderId").asString());
        assertEquals("PAID", safe.path("orderStatus").asString());
        assertFalse(result.contains("internal-user-1"));
        assertFalse(invocation.traces().get(1).payload().contains("internal-user-1"));
    }

    private SpringAiAgentTurnCoordinator.WorkflowInvocation invocation() {
        return new SpringAiAgentTurnCoordinator.WorkflowInvocation(
                null, Clock.fixed(NOW, ZoneOffset.UTC), AgentRuntimeMetrics.noop());
    }
}
