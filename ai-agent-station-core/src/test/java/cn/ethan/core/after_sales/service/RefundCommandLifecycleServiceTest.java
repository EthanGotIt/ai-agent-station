package cn.ethan.core.after_sales.service;

import cn.ethan.core.after_sales.enums.AfterSalesCaseStatusEnum;
import cn.ethan.core.after_sales.enums.AfterSalesHandlingModeEnum;
import cn.ethan.core.after_sales.enums.RefundReasonEnum;
import cn.ethan.core.after_sales.model.AfterSalesCaseModel;
import cn.ethan.core.after_sales.model.RefundCommandModel;
import cn.ethan.core.after_sales.model.RefundCommandResultModel;
import cn.ethan.core.after_sales.port.AfterSalesCaseGateway;
import cn.ethan.core.after_sales.port.RefundCommandGateway;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 退款命令生命周期测试：验证领取、有限重试、失败终态和成功终态的双对象收敛。
 *
 * @author ethan
 * @date 2026-08-12
 */
class RefundCommandLifecycleServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-12T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void retriesBeforeLimitThenMarksBothCommandAndCaseFailed() {
        State state = new State();
        RefundCommandLifecycleService service = service(state, 2);

        RefundCommandResultModel first = service.claimDue(1).get(0);
        service.fail(first, "CHANNEL_TEMPORARY_FAILURE");
        assertEquals("RETRY_WAIT", state.command.status());
        assertEquals(AfterSalesCaseStatusEnum.REFUND_PROCESSING, state.caseModel.status());

        RefundCommandResultModel reclaimed = state.command.claimed(NOW.plusSeconds(30), NOW.plusSeconds(16));
        state.command = reclaimed;
        service.fail(reclaimed, "CHANNEL_TEMPORARY_FAILURE");

        assertEquals("FAILED", state.command.status());
        assertEquals(AfterSalesCaseStatusEnum.REFUND_FAILED, state.caseModel.status());
        assertEquals("CHANNEL_TEMPORARY_FAILURE", state.caseModel.failureCode());
    }

    @Test
    void completesProcessingCommandAndCase() {
        State state = new State();
        RefundCommandLifecycleService service = service(state, 3);
        RefundCommandResultModel claimed = service.claimDue(1).get(0);

        service.complete(claimed);

        assertEquals("COMPLETED", state.command.status());
        assertEquals(AfterSalesCaseStatusEnum.COMPLETED, state.caseModel.status());
    }

    private RefundCommandLifecycleService service(State state, int maxAttempts) {
        return new RefundCommandLifecycleService(new Cases(state), new Refunds(state), CLOCK, maxAttempts,
                Duration.ofSeconds(15), Duration.ofSeconds(30));
    }

    private static final class State {

        private AfterSalesCaseModel caseModel = new AfterSalesCaseModel(
                "case-1", "run-1", "user-1", "ORDER-PAID-001", RefundReasonEnum.NOT_RECEIVED,
                "包裹未送达", AfterSalesHandlingModeEnum.MANUAL_REVIEW, AfterSalesCaseStatusEnum.REFUND_PROCESSING,
                new BigDecimal("99.00"), "CNY", "refund-1", 0, NOW, NOW
        );
        private RefundCommandResultModel command = new RefundCommandResultModel(
                "refund-1", "case-1", "run-1", "ORDER-PAID-001", "user-1", "PENDING",
                new BigDecimal("99.00"), "CNY", "", 0, NOW, null, "", 0, NOW, NOW
        );

    }

    private static final class Cases implements AfterSalesCaseGateway {

        private final State state;

        private Cases(State state) {
            this.state = state;
        }

        @Override
        public Optional<AfterSalesCaseModel> findByOrder(String orderId, String userId) {
            return Optional.of(state.caseModel);
        }

        @Override
        public Optional<AfterSalesCaseModel> findByWorkflowRunId(String workflowRunId) {
            return Optional.of(state.caseModel);
        }

        @Override
        public Optional<AfterSalesCaseModel> findByCaseId(String caseId) {
            return Optional.of(state.caseModel);
        }

        @Override
        public AfterSalesCaseModel create(AfterSalesCaseModel value) {
            state.caseModel = value;
            return value;
        }

        @Override
        public boolean update(AfterSalesCaseModel expected, AfterSalesCaseModel updated) {
            if (state.caseModel.version() != expected.version()) return false;
            state.caseModel = updated;
            return true;
        }
    }

    private static final class Refunds implements RefundCommandGateway {

        private final State state;

        private Refunds(State state) {
            this.state = state;
        }

        @Override
        public RefundCommandResultModel create(RefundCommandModel value) {
            return state.command;
        }

        @Override
        public Optional<RefundCommandResultModel> findByOrder(String orderId, String userId) {
            return Optional.of(state.command);
        }

        @Override
        public Optional<RefundCommandResultModel> findByCaseId(String caseId) {
            return Optional.of(state.command);
        }

        @Override
        public List<RefundCommandResultModel> claimDue(Instant now, Instant leaseUntil, int limit) {
            List<RefundCommandResultModel> result = new ArrayList<>();
            try {
                state.command = state.command.claimed(leaseUntil, now);
                result.add(state.command);
            } catch (IllegalStateException notClaimable) {
                // 测试内存网关与生产网关一样，仅返回成功领取的命令。
            }
            return result;
        }

        @Override
        public boolean update(RefundCommandResultModel expected, RefundCommandResultModel updated) {
            if (state.command.version() != expected.version()) return false;
            state.command = updated;
            return true;
        }
    }
}
