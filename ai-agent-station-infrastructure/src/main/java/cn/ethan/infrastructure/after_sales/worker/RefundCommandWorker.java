package cn.ethan.infrastructure.after_sales.worker;

import cn.ethan.core.after_sales.model.RefundCommandResultModel;
import cn.ethan.core.after_sales.model.RefundExecutionResultModel;
import cn.ethan.core.after_sales.port.RefundExecutor;
import cn.ethan.core.after_sales.service.RefundCommandLifecycleService;
import cn.ethan.infrastructure.after_sales.manager.RefundCommandSettlementManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 退款命令后台执行器：先领取持久化任务，再在事务外调用渠道，最后事务化收敛结果。
 *
 * @author ethan
 * @date 2026-08-12
 */
public final class RefundCommandWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(RefundCommandWorker.class);

    private final RefundCommandLifecycleService lifecycle;
    private final RefundExecutor executor;
    private final RefundCommandSettlementManager settlements;
    private final int batchSize;

    public RefundCommandWorker(
            RefundCommandLifecycleService lifecycle,
            RefundExecutor executor,
            RefundCommandSettlementManager settlements,
            int batchSize
    ) {
        this.lifecycle = lifecycle;
        this.executor = executor;
        this.settlements = settlements;
        this.batchSize = batchSize;
    }

    @Scheduled(
            scheduler = "taskScheduler",
            initialDelayString = "${ai-agent.after-sales.refund-worker.initial-delay:PT10S}",
            fixedDelayString = "${ai-agent.after-sales.refund-worker.poll-interval:PT5S}"
    )
    public void poll() {
        runOnce();
    }

    public void runOnce() {
        for (RefundCommandResultModel command : lifecycle.claimDue(batchSize)) {
            execute(command);
        }
    }

    private void execute(RefundCommandResultModel command) {
        RefundExecutionResultModel result;
        try {
            result = executor.execute(command);
        } catch (RuntimeException failure) {
            result = RefundExecutionResultModel.failed("REFUND_EXECUTOR_EXCEPTION");
        }
        try {
            if (result.completed()) {
                settlements.complete(command);
            } else {
                settlements.fail(command, result.failureCode());
            }
        } catch (RuntimeException settlementFailure) {
            LOGGER.warn(
                    "退款命令状态收敛失败，refundId={}, exception={}",
                    command.refundId(), settlementFailure.getClass().getSimpleName()
            );
        }
    }
}
