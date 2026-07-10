package cn.ethan.ai.domain.agent.port.driven;

import cn.ethan.ai.domain.agent.model.AfterSalesToolCapability;
import cn.ethan.ai.domain.agent.model.AfterSalesToolContext;
import cn.ethan.ai.domain.agent.model.AfterSalesToolRequest;
import cn.ethan.ai.domain.agent.model.AfterSalesToolResult;

import java.util.Set;

public interface IAfterSalesToolPort {

    default Set<AfterSalesToolCapability> supportedTools() {
        return Set.of(AfterSalesToolCapability.QUERY_ORDER);
    }

    default AfterSalesToolResult executeReadOnly(AfterSalesToolRequest request, AfterSalesToolContext context) {
        return executeOrderQuery(request, context.userId(), "");
    }

    @Deprecated(forRemoval = true)
    default AfterSalesToolRequest proposeOrderQuery(String userMessage,
                                                    String userId,
                                                    String sessionId,
                                                    String orderIdHint,
                                                    String refundReason,
                                                    String correction) {
        throw new UnsupportedOperationException("Use executeReadOnly with a policy-approved request");
    }

    @Deprecated(forRemoval = true)
    default AfterSalesToolResult executeOrderQuery(AfterSalesToolRequest request, String userId, String userMessage) {
        throw new UnsupportedOperationException("Use executeReadOnly with a server-side tool context");
    }
}
