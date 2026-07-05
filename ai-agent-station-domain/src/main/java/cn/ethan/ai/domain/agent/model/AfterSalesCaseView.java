package cn.ethan.ai.domain.agent.model;

import cn.ethan.ai.types.common.id.CaseId;
import cn.ethan.ai.types.common.id.OrderId;
import cn.ethan.ai.types.common.id.SessionId;
import cn.ethan.ai.types.common.id.UserId;

/**
 * 售后Case视图对象。
 */
public record AfterSalesCaseView(CaseId caseId,
                                 UserId userId,
                                 SessionId sessionId,
                                 OrderId orderId,
                                 String stage,
                                 String checkpointId,
                                 String nextNode,
                                 String terminalReason,
                                 String commandId) {

    public String caseIdValue() {
        return caseId == null ? null : caseId.value();
    }

    public String userIdValue() {
        return userId == null ? null : userId.value();
    }

    public String sessionIdValue() {
        return sessionId == null ? null : sessionId.value();
    }

    public String orderIdValue() {
        return orderId == null ? null : orderId.value();
    }

    public static AfterSalesCaseView of(String caseId,
                                        String userId,
                                        String sessionId,
                                        String orderId,
                                        String stage,
                                        String checkpointId,
                                        String nextNode,
                                        String terminalReason,
                                        String commandId) {
        return new AfterSalesCaseView(
                CaseId.of(caseId),
                UserId.of(userId),
                SessionId.of(sessionId),
                orderId == null || orderId.isBlank() ? null : OrderId.of(orderId),
                stage,
                checkpointId,
                nextNode,
                terminalReason,
                commandId
        );
    }
}
