package cn.ethan.infrastructure.agent.coordination.springai;

import cn.ethan.core.agent.execution.AgentRuntimeMetrics;
import cn.ethan.core.agent.event.AgentThreadEventGateway;
import cn.ethan.core.agent.thread.AgentItemJournal;
import cn.ethan.core.agent.thread.AgentItemModel;
import cn.ethan.core.agent.thread.AgentItemStore;
import cn.ethan.core.agent.thread.AgentItemTypeEnum;
import cn.ethan.core.agent.thread.AgentThreadModel;
import cn.ethan.core.agent.thread.AgentTurnModel;
import cn.ethan.core.agent.workflow.AgentWorkflowEngine;
import cn.ethan.core.commerce.order.OrderLookupResultModel;
import cn.ethan.core.commerce.order.OrderSnapshotModel;
import cn.ethan.core.commerce.order.OrderGateway;
import cn.ethan.core.commerce.order.OrderSearchResultModel;
import cn.ethan.core.commerce.order.OrderStatusEnum;
import cn.ethan.infrastructure.agent.thread.persistence.JacksonAgentItemPayloadCodec;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void blankRefundReasonIsPassedToWorkflowForQuestionCardCompletion() {
        AtomicInteger engineCalls = new AtomicInteger();
        SpringAiAgentTurnCoordinator.WorkflowInvocation invocation = invocation();
        AgentWorkflowEngine engine = new AgentWorkflowEngine() {
            @Override
            public StartResult start(
                    AgentThreadModel thread, AgentTurnModel turn, String operation, Map<String, String> arguments
            ) {
                engineCalls.incrementAndGet();
                assertEquals("ORDER_SERVICE", operation);
                assertEquals("REFUND", arguments.get("intent"));
                assertEquals("ORDER-1", arguments.get("orderId"));
                assertFalse(arguments.containsKey("reason"));
                return new StartResult("run-1", null, null);
            }

            @Override
            public ResumeResult resume(AgentThreadModel thread, AgentTurnModel turn, Map<String, String> answers) {
                throw new UnsupportedOperationException();
            }
        };
        SpringAiAgentTurnCoordinator.WorkflowTools tools = new SpringAiAgentTurnCoordinator.WorkflowTools(
                null, null, engine, invocation);

        tools.startOrderService("REFUND", "ORDER-1", null, null, null, null,
                null, null, null, " ");
        tools.startOrderService("REFUND", "ORDER-1", null, null, null, null,
                null, null, null, " ");

        assertEquals(1, engineCalls.get());
        assertEquals(2, invocation.traces().size());
    }

    @Test
    void completeAgentCycleAcceptsOnlyFinish() {
        SpringAiAgentTurnCoordinator.WorkflowInvocation invocation = invocation();
        SpringAiAgentTurnCoordinator.ControlTools tools = new SpringAiAgentTurnCoordinator.ControlTools(invocation);

        assertThrows(IllegalArgumentException.class,
                () -> tools.completeAgentCycle("ASK_USER", "请补充信息"));
        assertEquals("Agent 决策已记录。", tools.completeAgentCycle("FINISH", "已完成查询"));
        assertEquals("Agent 决策已记录。", tools.completeAgentCycle("FINISH", "不应覆盖"));
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

    @Test
    void searchToolReturnsStructuredOrderFactAndPersistsSafeProjection() throws Exception {
        SpringAiAgentTurnCoordinator.WorkflowInvocation invocation = invocation();
        OrderGateway orders = new OrderGateway() {
            @Override
            public OrderLookupResultModel findOrder(String orderId, String userId) {
                return OrderLookupResultModel.notFound();
            }

            @Override
            public OrderSearchResultModel searchOrders(
                    cn.ethan.core.commerce.order.OrderSearchCriteria criteria, String userId) {
                return OrderSearchResultModel.success(List.of(new OrderSnapshotModel(
                        "ORDER-SEARCH-001", "internal-user-1", OrderStatusEnum.PAID, null,
                        NOW, null, null, "待发货", new BigDecimal("99.00"), "CNY", "无线耳机", null)));
            }
        };
        SpringAiAgentTurnCoordinator.ReadOnlyTools tools = new SpringAiAgentTurnCoordinator.ReadOnlyTools(
                "user-1", orders, (orderId, userId) -> List.of(), invocation);

        String result = tools.searchOrders(
                "2026-08-21", "2026-08-22", "50", "120", "PAID", "耳机", "");

        JsonNode safe = new ObjectMapper().readTree(result);
        assertEquals("SUCCESS", safe.path("status").asString());
        assertEquals("ORDER-SEARCH-001", safe.path("orders").get(0).path("orderId").asString());
        assertFalse(result.contains("internal-user-1"));
        assertEquals("ORDER_LIST", invocation.traces().get(1).type());
        assertFalse(invocation.traces().get(1).payload().contains("internal-user-1"));
        assertThrows(IllegalArgumentException.class, () -> tools.searchOrders(
                null, null, null, null, null, null, "not-a-number"));
    }

    @Test
    void boundsEscapedToolResultByFinalJsonLengthAndKeepsCorrelation() throws Exception {
        String bounded = SpringAiOrderToolSupport.boundToolResult(
                "lookup_order", "inv-1", "SUCCESS", "\\\"\\n".repeat(2_000), 256);

        assertTrue(bounded.length() <= 256);
        JsonNode json = new ObjectMapper().readTree(bounded);
        assertEquals("lookup_order", json.path("tool").asString());
        assertEquals("inv-1", json.path("invocationId").asString());
        assertTrue(json.path("truncated").asBoolean());
    }

    @Test
    void escapesJsonControlCharactersForToolFacts() {
        assertEquals("\\\"\\\\\\b\\f\\n\\r\\t\\u0000\\u001f",
                SpringAiOrderToolSupport.escapeJson("\"\\\b\f\n\r\t\u0000\u001f"));
    }

    @Test
    void productionInvocationUsesTypedCodecForToolFacts() {
        List<AgentItemModel> persisted = new ArrayList<>();
        AtomicLong nextSequence = new AtomicLong();
        AgentItemStore items = new AgentItemStore() {
            @Override
            public long appendItem(AgentItemModel item) {
                long sequence = nextSequence.incrementAndGet();
                persisted.add(new AgentItemModel(item.itemId(), item.threadId(), item.turnId(), sequence,
                        item.type(), item.payload(), item.createdAt()));
                return sequence;
            }

            @Override
            public List<AgentItemModel> listItems(String userId, String threadId, long afterSequence, int limit) {
                return List.of();
            }
        };
        AgentItemJournal journal = new AgentItemJournal() {
            @Override
            public AgentItemModel append(AgentItemModel item) {
                items.appendItem(item);
                return persisted.get(persisted.size() - 1);
            }

            @Override
            public AgentItemStore.AppendResult appendIfAbsent(AgentItemModel item) {
                AgentItemModel saved = append(item);
                return new AgentItemStore.AppendResult(saved, true);
            }
        };
        AgentThreadEventGateway events = event -> { };
        JacksonAgentItemPayloadCodec codec = new JacksonAgentItemPayloadCodec(new ObjectMapper());
        SpringAiAgentTurnCoordinator.WorkflowInvocation invocation =
                new SpringAiAgentTurnCoordinator.WorkflowInvocation(
                        null, Clock.fixed(NOW, ZoneOffset.UTC), AgentRuntimeMetrics.noop(),
                        new AgentThreadModel("thread-1", "user-1", "测试",
                                cn.ethan.core.agent.thread.AgentThreadStatusEnum.ACTIVE,
                                null, null, 0, NOW, NOW),
                        new AgentTurnModel("turn-1", "thread-1", "user-1", "request-1", "查询订单",
                                cn.ethan.core.agent.thread.AgentTurnStatusEnum.ACTIVE,
                                0, null, null, NOW, NOW, null),
                        items, journal, codec, events, 512);

        SpringAiAgentTurnCoordinator.ReadOnlyTools tools = new SpringAiAgentTurnCoordinator.ReadOnlyTools(
                "user-1", (orderId, userId) -> OrderLookupResultModel.notFound(),
                (orderId, userId) -> List.of(), invocation);
        tools.lookupOrder("ORDER-1");

        assertEquals(2, persisted.size());
        assertEquals("TOOL_CALL", persisted.get(0).type().name());
        assertEquals("lookup_order", codec.decode(AgentItemTypeEnum.TOOL_CALL,
                persisted.get(0).payloadJson()).path("tool").asString());
        assertEquals("SUCCESS", codec.decode(AgentItemTypeEnum.TOOL_RESULT,
                persisted.get(1).payloadJson()).path("status").asString());
        assertFalse(codec.decode(AgentItemTypeEnum.TOOL_CALL,
                persisted.get(0).payloadJson()).has("type"));
    }

    private SpringAiAgentTurnCoordinator.WorkflowInvocation invocation() {
        return new SpringAiAgentTurnCoordinator.WorkflowInvocation(
                null, Clock.fixed(NOW, ZoneOffset.UTC), AgentRuntimeMetrics.noop());
    }
}
