package cn.ethan.ai.test.infrastructure;

import cn.ethan.ai.domain.agent.port.driven.IAfterSalesToolPort;
import cn.ethan.ai.domain.agent.model.AfterSalesOrderSnapshot;
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
            public AfterSalesToolRequest proposeOrderQuery(String userMessage, String userId, String sessionId,
                                                           String orderIdHint, String refundReason,
                                                           String correction) {
                return new AfterSalesToolRequest("call-1", "query_order", "{\"orderId\":\"ORDER-1\"}");
            }

            @Override
            public AfterSalesToolResult executeOrderQuery(AfterSalesToolRequest request,
                                                          String userId,
                                                          String userMessage) {
                return AfterSalesToolResult.success("{}",
                        new AfterSalesOrderSnapshot("ORDER-1", userId, "PAID", null));
            }
        };
        FaultInjectingAfterSalesToolAdapter adapter =
                new FaultInjectingAfterSalesToolAdapter(delegate, "TIMEOUT", 1);
        AfterSalesToolRequest request = delegate.proposeOrderQuery("退款", "user-1", "session-1", "ORDER-1", null, null);

        Assertions.assertEquals("TIMEOUT", adapter.executeOrderQuery(request, "user-1", "退款").errorType());
        Assertions.assertTrue(adapter.executeOrderQuery(request, "user-1", "退款").success());
    }
}
