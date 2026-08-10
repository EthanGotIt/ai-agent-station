package cn.ethan.core.after_sales.port;

import cn.ethan.core.after_sales.model.RefundCommandModel;
import cn.ethan.core.after_sales.model.RefundCommandResultModel;

import java.util.Optional;

/**
 * 退款命令网关：隔离本地演示退款写入和未来真实支付渠道。
 *
 * @author ethan
 * @date 2026-08-07
 */
public interface RefundCommandGateway {

    RefundCommandResultModel create(RefundCommandModel command);

    Optional<RefundCommandResultModel> findByOrder(String orderId, String userId);
}
