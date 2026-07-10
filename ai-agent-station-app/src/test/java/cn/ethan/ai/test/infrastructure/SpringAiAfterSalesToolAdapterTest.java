package cn.ethan.ai.test.infrastructure;

import cn.ethan.ai.domain.agent.model.AfterSalesOrderSnapshot;
import cn.ethan.ai.domain.agent.model.AfterSalesLogisticsSnapshot;
import cn.ethan.ai.domain.agent.model.AfterSalesRefundHistorySnapshot;
import cn.ethan.ai.domain.agent.model.AfterSalesToolContext;
import cn.ethan.ai.domain.agent.model.AfterSalesToolRequest;
import cn.ethan.ai.domain.agent.model.AfterSalesToolResult;
import cn.ethan.ai.domain.agent.port.driven.IOrderGateway;
import cn.ethan.ai.infrastructure.adapter.ai.SpringAiAfterSalesToolAdapter;
import cn.ethan.ai.infrastructure.observability.AfterSalesRuntimeMetrics;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.tool.ToolCallingManager;

import java.util.Optional;
import java.time.LocalDateTime;

public class SpringAiAfterSalesToolAdapterTest {

    @Test
    void shouldExecuteApprovedOrderRequestWithPrivateUserContext() {
        SpringAiAfterSalesToolAdapter adapter = new SpringAiAfterSalesToolAdapter(
                new OrderOnlyRepository(),
                ToolCallingManager.builder().build(),
                null,
                "stub"
        );

        AfterSalesToolRequest request = new AfterSalesToolRequest("call-1", "query_order", "{\"orderId\":\"ORDER-1\"}");
        AfterSalesToolResult result = adapter.executeReadOnly(request,
                new AfterSalesToolContext("case-1", "user-1", "turn-1"));

        Assertions.assertEquals("query_order", request.toolName());
        Assertions.assertTrue(result.success());
        Assertions.assertEquals("ORDER-1", result.order().orderId());
        Assertions.assertEquals("user-1", result.order().ownerId());
        Assertions.assertFalse(result.outputJson().contains("user-2"));
    }

    @Test
    void shouldExecuteOnlyConfiguredReadOnlyEvidenceTools() {
        SpringAiAfterSalesToolAdapter adapter = new SpringAiAfterSalesToolAdapter(
                new EvidenceRepository(), AfterSalesRuntimeMetrics.noop(),
                "query_order,query_logistics,query_refund_history");
        AfterSalesToolContext context = new AfterSalesToolContext("case-1", "user-1", "turn-1");

        AfterSalesToolResult logistics = adapter.executeReadOnly(
                new AfterSalesToolRequest("call-2", "query_logistics", "{\"orderId\":\"ORDER-1\"}"), context);
        AfterSalesToolResult refundHistory = adapter.executeReadOnly(
                new AfterSalesToolRequest("call-3", "query_refund_history", "{\"orderId\":\"ORDER-1\"}"), context);

        Assertions.assertTrue(logistics.success());
        Assertions.assertEquals("DELIVERED", logistics.evidence().fields().get("deliveryStatus"));
        Assertions.assertTrue(refundHistory.success());
        Assertions.assertEquals(1, refundHistory.evidence().fields().get("completedRefundCount"));
    }

    private static class OrderOnlyRepository implements IOrderGateway {
        @Override
        public Optional<AfterSalesOrderSnapshot> findOrder(String orderId, String requesterId) {
            return Optional.of(new AfterSalesOrderSnapshot(orderId, "user-1", "PAID", null));
        }
    }

    private static final class EvidenceRepository extends OrderOnlyRepository {
        @Override
        public Optional<AfterSalesLogisticsSnapshot> findLogistics(String orderId, String requesterId) {
            return Optional.of(new AfterSalesLogisticsSnapshot(orderId, "DELIVERED", LocalDateTime.of(2026, 7, 1, 10, 0), "NONE"));
        }

        @Override
        public Optional<AfterSalesRefundHistorySnapshot> findRefundHistory(String orderId, String requesterId) {
            return Optional.of(new AfterSalesRefundHistorySnapshot(orderId, false, 1, "SUCCEEDED"));
        }
    }
}
