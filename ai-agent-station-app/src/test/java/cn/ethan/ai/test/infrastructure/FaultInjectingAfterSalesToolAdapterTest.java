package cn.ethan.ai.test.infrastructure;

import cn.ethan.ai.domain.agent.port.driven.IAfterSalesToolPort;
import cn.ethan.ai.domain.agent.model.AfterSalesToolContext;
import cn.ethan.ai.domain.agent.model.AfterSalesToolRequest;
import cn.ethan.ai.domain.agent.model.AfterSalesToolResult;
import cn.ethan.ai.infrastructure.adapter.ai.FaultInjectingAfterSalesToolAdapter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class FaultInjectingAfterSalesToolAdapterTest {

    @Test
    void shouldInjectOneTimeoutThenRecoverToDelegate() {
        IAfterSalesToolPort delegate = new IAfterSalesToolPort() {
            @Override
            public AfterSalesToolResult executeReadOnly(AfterSalesToolRequest request, AfterSalesToolContext context) {
                return AfterSalesToolResult.success("{}", new cn.ethan.ai.domain.agent.model.ToolEvidence("query_order",
                        java.util.Map.of("orderId", "ORDER-1", "ownerId", context.userId(), "status", "PAID")));
            }
        };
        FaultInjectingAfterSalesToolAdapter adapter =
                new FaultInjectingAfterSalesToolAdapter(delegate, "TIMEOUT", 1);
        AfterSalesToolRequest request = new AfterSalesToolRequest("call-1", "query_order", "{\"orderId\":\"ORDER-1\"}");
        AfterSalesToolContext context = new AfterSalesToolContext("case-1", "user-1", "turn-1");

        Assertions.assertEquals("TIMEOUT", adapter.executeReadOnly(request, context).errorType());
        Assertions.assertTrue(adapter.executeReadOnly(request, context).success());
    }
}
