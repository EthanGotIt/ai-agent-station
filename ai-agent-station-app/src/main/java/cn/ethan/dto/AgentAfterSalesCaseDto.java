package cn.ethan.dto;

import cn.ethan.core.after_sales.model.AfterSalesCaseModel;
import cn.ethan.core.after_sales.model.RefundCommandResultModel;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 售后申请响应 DTO：供客户状态展示和操作员审核台读取同一份脱敏业务状态。
 *
 * @author ethan
 * @date 2026-08-12
 */
public record AgentAfterSalesCaseDto(
        String caseId,
        String workflowRunId,
        String userId,
        String orderId,
        String reason,
        String description,
        String handlingMode,
        String status,
        BigDecimal amount,
        String currency,
        String refundId,
        String operatorId,
        String decisionId,
        String decisionNote,
        Instant reviewedAt,
        String failureCode,
        long version,
        Instant createdAt,
        Instant updatedAt,
        AgentAfterSalesRefundCommandDto refundCommand
) {

    public static AgentAfterSalesCaseDto from(
            AfterSalesCaseModel model,
            RefundCommandResultModel command
    ) {
        return new AgentAfterSalesCaseDto(
                model.caseId(), model.workflowRunId(), model.userId(), model.orderId(), model.reason().name(),
                model.description(), model.handlingMode().name(), model.status().name(), model.amount(),
                model.currency(), model.refundId(), model.operatorId(), model.decisionId(), model.decisionNote(),
                model.reviewedAt(), model.failureCode(), model.version(), model.createdAt(), model.updatedAt(),
                command == null ? null : AgentAfterSalesRefundCommandDto.from(command)
        );
    }
}
