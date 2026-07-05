package cn.ethan.ai.domain.agent.model;

import cn.ethan.ai.types.common.id.CaseId;
import cn.ethan.ai.types.common.id.OrderId;
import cn.ethan.ai.types.common.id.UserId;

/**
 * 售后Agent恢复命令。
 */
public record AfterSalesResumeCommand(CaseId caseId,
                                      String checkpointId,
                                      ResumeAction action,
                                      OrderId orderId,
                                      String refundReason,
                                      UserId actorId,
                                      String actorRole) {

    public enum ResumeAction {
        SUPPLY_INFO,
        APPROVE,
        REJECT
    }

    public String caseIdValue() {
        return caseId == null ? null : caseId.value();
    }

    public String orderIdValue() {
        return orderId == null ? null : orderId.value();
    }

    public String actorIdValue() {
        return actorId == null ? null : actorId.value();
    }

    public static AfterSalesResumeCommand of(String caseId,
                                             String checkpointId,
                                             ResumeAction action,
                                             String orderId,
                                             String refundReason,
                                             String actorId,
                                             String actorRole) {
        return new AfterSalesResumeCommand(
                CaseId.of(caseId),
                checkpointId,
                action,
                orderId == null || orderId.isBlank() ? null : OrderId.of(orderId),
                refundReason,
                UserId.of(actorId),
                actorRole
        );
    }
}
