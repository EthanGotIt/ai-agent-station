package cn.ethan.infrastructure.after_sales.executor;

import cn.ethan.core.after_sales.model.RefundCommandResultModel;
import cn.ethan.core.after_sales.model.RefundExecutionResultModel;
import cn.ethan.core.after_sales.port.RefundExecutor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 本地退款执行器：在非生产环境确定性模拟渠道完成，保留真实支付渠道替换边界。
 *
 * @author ethan
 * @date 2026-08-12
 */
@Component
@ConditionalOnProperty(
        name = "ai-agent.after-sales.refund-channel.mode",
        havingValue = "local",
        matchIfMissing = true
)
public final class LocalRefundExecutor implements RefundExecutor {

    @Override
    public RefundExecutionResultModel execute(RefundCommandResultModel command) {
        return RefundExecutionResultModel.succeeded();
    }
}
