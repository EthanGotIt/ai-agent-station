package cn.ethan.infrastructure.after_sales.worker;

import cn.ethan.core.after_sales.model.RefundCommandResultModel;
import cn.ethan.core.after_sales.model.RefundExecutionResultModel;
import cn.ethan.core.after_sales.port.RefundExecutor;
import cn.ethan.core.after_sales.service.RefundCommandLifecycleService;
import cn.ethan.infrastructure.after_sales.manager.RefundCommandSettlementManager;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 退款命令 Worker 测试：验证执行异常降级、结算隔离和批次继续处理。
 *
 * @author ethan
 * @date 2026-08-12
 */
class RefundCommandWorkerTest {

    @Test
    void isolatesExecutorAndSettlementFailuresWithoutStoppingBatch() {
        RefundCommandLifecycleService lifecycle = mock(RefundCommandLifecycleService.class);
        RefundExecutor executor = mock(RefundExecutor.class);
        RefundCommandSettlementManager settlements = mock(RefundCommandSettlementManager.class);
        RefundCommandResultModel first = command("REFUND-001", "CASE-001");
        RefundCommandResultModel second = command("REFUND-002", "CASE-002");
        when(lifecycle.claimDue(2)).thenReturn(List.of(first, second));
        when(executor.execute(first)).thenThrow(new IllegalStateException("channel unavailable"));
        when(executor.execute(second)).thenReturn(RefundExecutionResultModel.succeeded());
        doThrow(new IllegalStateException("settlement conflict"))
                .when(settlements).fail(first, "REFUND_EXECUTOR_EXCEPTION");

        new RefundCommandWorker(lifecycle, executor, settlements, 2).runOnce();

        verify(settlements).fail(first, "REFUND_EXECUTOR_EXCEPTION");
        verify(executor).execute(second);
        verify(settlements).complete(second);
    }

    private RefundCommandResultModel command(String refundId, String caseId) {
        Instant now = Instant.parse("2026-08-12T12:00:00Z");
        return new RefundCommandResultModel(
                refundId, caseId, "RUN-" + caseId, "ORDER-" + caseId, "user-1", "PROCESSING",
                new BigDecimal("99.00"), "CNY", "", 1, now, now.plusSeconds(30), "", 1,
                now, now
        );
    }
}
