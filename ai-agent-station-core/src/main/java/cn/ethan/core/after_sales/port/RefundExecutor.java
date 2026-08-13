package cn.ethan.core.after_sales.port;

import cn.ethan.core.after_sales.model.RefundCommandResultModel;
import cn.ethan.core.after_sales.model.RefundExecutionResultModel;

/**
 * 退款渠道执行器：隔离本地演示实现与未来真实支付渠道的调用细节。
 *
 * @author ethan
 * @date 2026-08-12
 */
public interface RefundExecutor {

    RefundExecutionResultModel execute(RefundCommandResultModel command);
}
