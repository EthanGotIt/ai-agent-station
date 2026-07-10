package cn.ethan.ai.test.evaluation;

import cn.ethan.ai.domain.agent.model.AfterSalesOrderSnapshot;
import cn.ethan.ai.domain.agent.model.AfterSalesToolContext;
import cn.ethan.ai.domain.agent.model.AfterSalesToolRequest;
import cn.ethan.ai.domain.agent.model.AfterSalesToolResult;
import cn.ethan.ai.domain.agent.port.driven.IOrderGateway;
import cn.ethan.ai.infrastructure.adapter.ai.SpringAiAfterSalesToolAdapter;
import cn.ethan.ai.infrastructure.observability.AfterSalesRuntimeMetrics;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Optional;

public class ModelToolCallSmokeIT {

    @Test
    void shouldExecuteApprovedOrderQueryWithoutCallingModelAgain() {
        SpringAiAfterSalesToolAdapter adapter = new SpringAiAfterSalesToolAdapter(
                new StubOrderGateway(),
                AfterSalesRuntimeMetrics.noop(),
                "query_order");

        AfterSalesToolRequest request = new AfterSalesToolRequest("call-1", "query_order", "{\"orderId\":\"ORDER-SMOKE-001\"}");
        AfterSalesToolResult result = adapter.executeReadOnly(request,
                new AfterSalesToolContext("case-smoke", "user-1", "turn-smoke"));
        Assertions.assertTrue(result.success());
        Assertions.assertEquals("ORDER-SMOKE-001", result.evidence().fields().get("orderId"));
    }

    private static final class StubOrderGateway implements IOrderGateway {
        @Override
        public Optional<AfterSalesOrderSnapshot> findOrder(String orderId, String requesterId) {
            return Optional.of(new AfterSalesOrderSnapshot(orderId, "user-1", "PAID", 5));
        }
    }
}
