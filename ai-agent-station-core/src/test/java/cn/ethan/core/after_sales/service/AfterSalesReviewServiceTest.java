package cn.ethan.core.after_sales.service;

import cn.ethan.core.after_sales.enums.AfterSalesCaseStatusEnum;
import cn.ethan.core.after_sales.enums.AfterSalesHandlingModeEnum;
import cn.ethan.core.after_sales.enums.AfterSalesReviewDecisionEnum;
import cn.ethan.core.after_sales.enums.RefundCommandStatusEnum;
import cn.ethan.core.after_sales.enums.RefundReasonEnum;
import cn.ethan.core.after_sales.exception.AfterSalesCaseConflictException;
import cn.ethan.core.after_sales.model.AfterSalesCaseModel;
import cn.ethan.core.after_sales.model.AfterSalesRefundRetryRequestModel;
import cn.ethan.core.after_sales.model.AfterSalesReviewRequestModel;
import cn.ethan.core.after_sales.model.RefundCommandModel;
import cn.ethan.core.after_sales.model.RefundCommandResultModel;
import cn.ethan.core.after_sales.port.AfterSalesCaseGateway;
import cn.ethan.core.after_sales.port.RefundCommandGateway;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 售后审核服务测试：验证人工决策、决策重放、版本冲突和失败重试的状态边界。
 *
 * @author ethan
 * @date 2026-08-12
 */
class AfterSalesReviewServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-12T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void approvesPendingCaseAndReplaysSameDecisionWithoutSecondCommand() {
        InMemoryCaseGateway cases = new InMemoryCaseGateway(caseModel(AfterSalesCaseStatusEnum.PENDING_REVIEW, 0));
        InMemoryRefundGateway refunds = new InMemoryRefundGateway();
        AfterSalesReviewService service = new AfterSalesReviewService(cases, refunds, CLOCK);
        AfterSalesReviewRequestModel request = new AfterSalesReviewRequestModel(
                "case-1", "decision-1", 0, AfterSalesReviewDecisionEnum.APPROVE, "材料齐全"
        );

        var first = service.review(request, "operator-1");
        var replay = service.review(request, "operator-1");

        assertEquals(AfterSalesCaseStatusEnum.REFUND_PROCESSING, first.caseModel().status());
        assertEquals("operator-1", first.caseModel().operatorId());
        assertEquals("decision-1", first.caseModel().decisionId());
        assertEquals(RefundCommandStatusEnum.PENDING, first.refundCommand().statusEnum());
        assertEquals(first.refundCommand().refundId(), replay.refundCommand().refundId());
        assertEquals(1, refunds.created);
    }

    @Test
    void rejectionRequiresNoteAndStaleDecisionConflicts() {
        InMemoryCaseGateway cases = new InMemoryCaseGateway(caseModel(AfterSalesCaseStatusEnum.PENDING_REVIEW, 2));
        AfterSalesReviewService service = new AfterSalesReviewService(cases, new InMemoryRefundGateway(), CLOCK);

        assertThrows(IllegalArgumentException.class, () -> new AfterSalesReviewRequestModel(
                "case-1", "decision-1", 2, AfterSalesReviewDecisionEnum.REJECT, ""
        ));
        assertThrows(AfterSalesCaseConflictException.class, () -> service.review(
                new AfterSalesReviewRequestModel(
                        "case-1", "decision-1", 1, AfterSalesReviewDecisionEnum.REJECT, "凭证不足"
                ), "operator-1"
        ));
    }

    @Test
    void approvalRejectsCaseWithoutRefundAmount() {
        AfterSalesCaseModel incomplete = new AfterSalesCaseModel(
                "case-1", "run-1", "user-1", "ORDER-PAID-001", RefundReasonEnum.NOT_RECEIVED,
                "包裹未送达", AfterSalesHandlingModeEnum.MANUAL_REVIEW,
                AfterSalesCaseStatusEnum.PENDING_REVIEW, null, "", "", 0, NOW, NOW
        );
        AfterSalesReviewService service = new AfterSalesReviewService(
                new InMemoryCaseGateway(incomplete), new InMemoryRefundGateway(), CLOCK
        );

        assertThrows(AfterSalesCaseConflictException.class, () -> service.review(
                new AfterSalesReviewRequestModel(
                        "case-1", "decision-1", 0, AfterSalesReviewDecisionEnum.APPROVE, "材料齐全"
                ), "operator-1"
        ));
    }

    @Test
    void requeuesFailedRefundWithIndependentRetryId() {
        InMemoryCaseGateway cases = new InMemoryCaseGateway(failedCase());
        InMemoryRefundGateway refunds = new InMemoryRefundGateway();
        refunds.byCase.put("case-1", failedCommand());
        AfterSalesReviewService service = new AfterSalesReviewService(cases, refunds, CLOCK);

        var result = service.retry(new AfterSalesRefundRetryRequestModel("case-1", "retry-1", 4), "operator-1");
        var replay = service.retry(new AfterSalesRefundRetryRequestModel("case-1", "retry-1", 5), "operator-1");

        assertEquals(AfterSalesCaseStatusEnum.REFUND_PROCESSING, result.caseModel().status());
        assertEquals(RefundCommandStatusEnum.PENDING, result.refundCommand().statusEnum());
        assertEquals("retry-1", result.refundCommand().retryId());
        assertEquals(result.refundCommand().version(), replay.refundCommand().version());
    }

    private AfterSalesCaseModel caseModel(AfterSalesCaseStatusEnum status, long version) {
        return new AfterSalesCaseModel(
                "case-1", "run-1", "user-1", "ORDER-PAID-001", RefundReasonEnum.NOT_RECEIVED,
                "包裹未送达", AfterSalesHandlingModeEnum.MANUAL_REVIEW, status,
                new BigDecimal("99.00"), "CNY", "", version, NOW, NOW
        );
    }

    private AfterSalesCaseModel failedCase() {
        return new AfterSalesCaseModel(
                "case-1", "run-1", "user-1", "ORDER-PAID-001", RefundReasonEnum.NOT_RECEIVED,
                "包裹未送达", AfterSalesHandlingModeEnum.MANUAL_REVIEW, AfterSalesCaseStatusEnum.REFUND_FAILED,
                new BigDecimal("99.00"), "CNY", "refund-1", "operator-1", "decision-1", "已批准", NOW,
                "REFUND_EXECUTOR_EXCEPTION", 4, NOW, NOW
        );
    }

    private RefundCommandResultModel failedCommand() {
        return new RefundCommandResultModel(
                "refund-1", "case-1", "run-1", "ORDER-PAID-001", "user-1", "FAILED",
                new BigDecimal("99.00"), "CNY", "", 3, NOW, null,
                "REFUND_EXECUTOR_EXCEPTION", 2, NOW, NOW
        );
    }

    private static final class InMemoryCaseGateway implements AfterSalesCaseGateway {

        private AfterSalesCaseModel current;

        private InMemoryCaseGateway(AfterSalesCaseModel current) {
            this.current = current;
        }

        @Override
        public Optional<AfterSalesCaseModel> findByOrder(String orderId, String userId) {
            return current.orderId().equals(orderId) && current.userId().equals(userId)
                    ? Optional.of(current) : Optional.empty();
        }

        @Override
        public Optional<AfterSalesCaseModel> findByWorkflowRunId(String workflowRunId) {
            return current.workflowRunId().equals(workflowRunId) ? Optional.of(current) : Optional.empty();
        }

        @Override
        public Optional<AfterSalesCaseModel> findByCaseId(String caseId) {
            return current.caseId().equals(caseId) ? Optional.of(current) : Optional.empty();
        }

        @Override
        public List<AfterSalesCaseModel> findPage(AfterSalesCaseStatusEnum status, int offset, int limit) {
            return List.of(current);
        }

        @Override
        public AfterSalesCaseModel create(AfterSalesCaseModel caseModel) {
            current = caseModel;
            return current;
        }

        @Override
        public boolean update(AfterSalesCaseModel expected, AfterSalesCaseModel updated) {
            if (current.version() != expected.version()) {
                return false;
            }
            current = updated;
            return true;
        }
    }

    private static final class InMemoryRefundGateway implements RefundCommandGateway {

        private final Map<String, RefundCommandResultModel> byCase = new HashMap<>();
        private int created;

        @Override
        public RefundCommandResultModel create(RefundCommandModel command) {
            return byCase.computeIfAbsent(command.caseId(), ignored -> {
                created++;
                return new RefundCommandResultModel(
                        "refund-" + created, command.caseId(), command.workflowRunId(), command.orderId(),
                        command.userId(), "PENDING", command.amount(), command.currency(), "", 0,
                        command.createdAt(), null, "", 0, command.createdAt(), command.createdAt()
                );
            });
        }

        @Override
        public Optional<RefundCommandResultModel> findByOrder(String orderId, String userId) {
            return byCase.values().stream().filter(command -> command.orderId().equals(orderId)
                    && command.userId().equals(userId)).findFirst();
        }

        @Override
        public Optional<RefundCommandResultModel> findByCaseId(String caseId) {
            return Optional.ofNullable(byCase.get(caseId));
        }

        @Override
        public boolean update(RefundCommandResultModel expected, RefundCommandResultModel updated) {
            return byCase.replace(expected.caseId(), expected, updated);
        }
    }
}
