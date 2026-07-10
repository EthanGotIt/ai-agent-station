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

    AfterSalesToolResult executeReadOnly(AfterSalesToolRequest request, AfterSalesToolContext context);
}
