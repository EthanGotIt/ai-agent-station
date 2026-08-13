package cn.ethan.core.after_sales.service;

import cn.ethan.core.after_sales.enums.AfterSalesCaseStatusEnum;
import cn.ethan.core.after_sales.enums.RefundCommandStatusEnum;
import cn.ethan.core.after_sales.exception.AfterSalesCaseConflictException;
import cn.ethan.core.after_sales.exception.AfterSalesCaseNotFoundException;
import cn.ethan.core.after_sales.model.AfterSalesCaseModel;
import cn.ethan.core.after_sales.model.RefundCommandResultModel;
import cn.ethan.core.after_sales.port.AfterSalesCaseGateway;
import cn.ethan.core.after_sales.port.RefundCommandGateway;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 退款命令生命周期服务：处理任务领取、执行完成和有限重试后的状态收敛。
 *
 * @author ethan
 * @date 2026-08-12
 */
public final class RefundCommandLifecycleService {

    private final AfterSalesCaseGateway cases;
    private final RefundCommandGateway refunds;
    private final Clock clock;
    private final int maxAttempts;
    private final Duration retryDelay;
    private final Duration leaseDuration;

    public RefundCommandLifecycleService(
            AfterSalesCaseGateway cases,
            RefundCommandGateway refunds,
            Clock clock,
            int maxAttempts,
            Duration retryDelay,
            Duration leaseDuration
    ) {
        if (maxAttempts < 1 || retryDelay == null || retryDelay.isNegative() || retryDelay.isZero()
                || leaseDuration == null || leaseDuration.isNegative() || leaseDuration.isZero()) {
            throw new IllegalArgumentException("refund worker policy is invalid");
        }
        this.cases = cases;
        this.refunds = refunds;
        this.clock = clock;
        this.maxAttempts = maxAttempts;
        this.retryDelay = retryDelay;
        this.leaseDuration = leaseDuration;
    }

    public List<RefundCommandResultModel> claimDue(int batchSize) {
        if (batchSize < 1) {
            return List.of();
        }
        Instant now = clock.instant();
        return refunds.claimDue(now, now.plus(leaseDuration), batchSize);
    }

    public void complete(RefundCommandResultModel command) {
        requireProcessing(command);
        Instant now = clock.instant();
        RefundCommandResultModel completed = command.completed(now);
        if (!refunds.update(command, completed)) {
            throw new AfterSalesCaseConflictException("refund command version has changed");
        }
        AfterSalesCaseModel caseModel = caseFor(command);
        if (caseModel.status() == AfterSalesCaseStatusEnum.COMPLETED) {
            return;
        }
        if (caseModel.status() != AfterSalesCaseStatusEnum.REFUND_PROCESSING
                || !cases.update(caseModel, caseModel.withCompleted(now))) {
            throw new AfterSalesCaseConflictException("after-sales case version has changed");
        }
    }

    public void fail(RefundCommandResultModel command, String failureCode) {
        requireProcessing(command);
        String code = normalizeFailureCode(failureCode);
        Instant now = clock.instant();
        if (command.attemptCount() < maxAttempts) {
            RefundCommandResultModel retryWaiting = command.retryWaiting(now.plus(retryDelay), code, now);
            if (!refunds.update(command, retryWaiting)) {
                throw new AfterSalesCaseConflictException("refund command version has changed");
            }
            return;
        }
        RefundCommandResultModel failed = command.failed(code, now);
        if (!refunds.update(command, failed)) {
            throw new AfterSalesCaseConflictException("refund command version has changed");
        }
        AfterSalesCaseModel caseModel = caseFor(command);
        if (caseModel.status() == AfterSalesCaseStatusEnum.REFUND_FAILED) {
            return;
        }
        if (caseModel.status() != AfterSalesCaseStatusEnum.REFUND_PROCESSING
                || !cases.update(caseModel, caseModel.withRefundFailure(code, now))) {
            throw new AfterSalesCaseConflictException("after-sales case version has changed");
        }
    }

    private AfterSalesCaseModel caseFor(RefundCommandResultModel command) {
        return cases.findByCaseId(command.caseId())
                .orElseThrow(() -> new AfterSalesCaseNotFoundException(command.caseId()));
    }

    private void requireProcessing(RefundCommandResultModel command) {
        if (command == null || command.statusEnum() != RefundCommandStatusEnum.PROCESSING) {
            throw new AfterSalesCaseConflictException("refund command is not processing");
        }
    }

    private String normalizeFailureCode(String failureCode) {
        if (failureCode == null || failureCode.isBlank()) {
            return "REFUND_EXECUTION_FAILED";
        }
        return failureCode.strip().substring(0, Math.min(failureCode.strip().length(), 64));
    }
}
