package cn.ethan.ai.domain.agent.adapter.port;

import cn.ethan.ai.domain.agent.model.AfterSalesToolRequest;
import cn.ethan.ai.domain.agent.model.AfterSalesToolResult;

public interface IAfterSalesToolPort {

    AfterSalesToolRequest proposeOrderQuery(String userMessage,
                                            String userId,
                                            String orderIdHint,
                                            String refundReason,
                                            String correction);

    AfterSalesToolResult executeOrderQuery(AfterSalesToolRequest request, String userId, String userMessage);
}
