package cn.ethan.infrastructure.after_sales.manager;

import cn.ethan.core.after_sales.model.RefundCommandResultModel;
import cn.ethan.core.after_sales.service.RefundCommandLifecycleService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 退款命令结果事务管理器：在渠道调用结束后原子收敛命令和售后申请状态。
 *
 * @author ethan
 * @date 2026-08-12
 */
@Component
public class RefundCommandSettlementManager {

    private final RefundCommandLifecycleService lifecycle;

    public RefundCommandSettlementManager(RefundCommandLifecycleService lifecycle) {
        this.lifecycle = lifecycle;
    }

    @Transactional
    public void complete(RefundCommandResultModel command) {
        lifecycle.complete(command);
    }

    @Transactional
    public void fail(RefundCommandResultModel command, String failureCode) {
        lifecycle.fail(command, failureCode);
    }
}
