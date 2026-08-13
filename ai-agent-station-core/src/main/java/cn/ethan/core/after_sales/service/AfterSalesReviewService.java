package cn.ethan.core.after_sales.service;

import cn.ethan.core.after_sales.enums.AfterSalesCaseStatusEnum;
import cn.ethan.core.after_sales.enums.AfterSalesReviewDecisionEnum;
import cn.ethan.core.after_sales.enums.RefundCommandStatusEnum;
import cn.ethan.core.after_sales.exception.AfterSalesCaseConflictException;
import cn.ethan.core.after_sales.exception.AfterSalesCaseNotFoundException;
import cn.ethan.core.after_sales.model.AfterSalesCaseModel;
import cn.ethan.core.after_sales.model.AfterSalesRefundRetryRequestModel;
import cn.ethan.core.after_sales.model.AfterSalesReviewRequestModel;
import cn.ethan.core.after_sales.model.AfterSalesReviewResultModel;
import cn.ethan.core.after_sales.model.RefundCommandModel;
import cn.ethan.core.after_sales.model.RefundCommandResultModel;
import cn.ethan.core.after_sales.port.AfterSalesCaseGateway;
import cn.ethan.core.after_sales.port.RefundCommandGateway;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * 售后审核服务：集中审核、幂等重放和人工重试的业务不变量。
 *
 * @author ethan
 * @date 2026-08-12
 */
public final class AfterSalesReviewService {

    private final AfterSalesCaseGateway cases;
    private final RefundCommandGateway refunds;
    private final Clock clock;

    public AfterSalesReviewService(
            AfterSalesCaseGateway cases,
            RefundCommandGateway refunds,
            Clock clock
    ) {
        this.cases = cases;
        this.refunds = refunds;
        this.clock = clock;
    }

    public AfterSalesReviewResultModel review(AfterSalesReviewRequestModel request, String operatorId) {
        String currentOperatorId = requiredOperator(operatorId);
        AfterSalesCaseModel current = cases.findByCaseId(request.caseId())
                .orElseThrow(() -> new AfterSalesCaseNotFoundException(request.caseId()));
        Optional<RefundCommandResultModel> existingCommand = refunds.findByCaseId(current.caseId());
        if (request.decisionId().equals(current.decisionId())) {
            return new AfterSalesReviewResultModel(current, existingCommand.orElse(null));
        }
        if (current.version() != request.expectedVersion()
                || current.status() != AfterSalesCaseStatusEnum.PENDING_REVIEW) {
            throw new AfterSalesCaseConflictException("after-sales case version or status has changed");
        }
        Instant now = clock.instant();
        if (request.decision() == AfterSalesReviewDecisionEnum.REJECT) {
            AfterSalesCaseModel updated = current.reviewed(
                    AfterSalesCaseStatusEnum.REJECTED, currentOperatorId, request.decisionId(),
                    request.note(), "", now
            );
            updateCase(current, updated);
            return new AfterSalesReviewResultModel(updated, null);
        }
        if (current.amount() == null || current.currency().isBlank()) {
            throw new AfterSalesCaseConflictException("after-sales case refund amount is incomplete");
        }
        RefundCommandResultModel command = refunds.create(new RefundCommandModel(
                current.workflowRunId(), current.caseId(), current.orderId(), current.userId(), current.reason(),
                current.amount(), current.currency(), now
        ));
        AfterSalesCaseModel updated = current.reviewed(
                AfterSalesCaseStatusEnum.REFUND_PROCESSING, currentOperatorId, request.decisionId(),
                request.note(), command.refundId(), now
        );
        updateCase(current, updated);
        return new AfterSalesReviewResultModel(updated, command);
    }

    public AfterSalesReviewResultModel retry(AfterSalesRefundRetryRequestModel request, String operatorId) {
        requiredOperator(operatorId);
        AfterSalesCaseModel current = cases.findByCaseId(request.caseId())
                .orElseThrow(() -> new AfterSalesCaseNotFoundException(request.caseId()));
        RefundCommandResultModel command = refunds.findByCaseId(current.caseId())
                .orElseThrow(() -> new AfterSalesCaseConflictException("refund command is unavailable"));
        if (request.retryId().equals(command.retryId())) {
            return new AfterSalesReviewResultModel(current, command);
        }
        if (current.version() != request.expectedVersion()
                || current.status() != AfterSalesCaseStatusEnum.REFUND_FAILED
                || command.statusEnum() != RefundCommandStatusEnum.FAILED) {
            throw new AfterSalesCaseConflictException("after-sales case cannot be retried");
        }
        Instant now = clock.instant();
        RefundCommandResultModel requeued = command.requeued(request.retryId(), now);
        if (!refunds.update(command, requeued)) {
            throw new AfterSalesCaseConflictException("refund command version has changed");
        }
        AfterSalesCaseModel updated = current.requeued(now);
        updateCase(current, updated);
        return new AfterSalesReviewResultModel(updated, requeued);
    }

    private void updateCase(AfterSalesCaseModel expected, AfterSalesCaseModel updated) {
        if (!cases.update(expected, updated)) {
            throw new AfterSalesCaseConflictException("after-sales case version has changed");
        }
    }

    private String requiredOperator(String operatorId) {
        if (operatorId == null || operatorId.isBlank() || operatorId.strip().length() > 128) {
            throw new IllegalArgumentException("X-Operator-Id is required and must not exceed 128 characters");
        }
        return operatorId.strip();
    }
}
