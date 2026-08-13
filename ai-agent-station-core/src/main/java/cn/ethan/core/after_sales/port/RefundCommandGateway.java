package cn.ethan.core.after_sales.port;

import cn.ethan.core.after_sales.model.RefundCommandModel;
import cn.ethan.core.after_sales.model.RefundCommandResultModel;

import java.time.Instant;
import java.util.List;
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

    default Optional<RefundCommandResultModel> findByCaseId(String caseId) {
        return Optional.empty();
    }

    /**
     * 以乐观版本领取到期任务；实现必须只返回当前调用成功占有租约的命令。
     */
    default List<RefundCommandResultModel> claimDue(Instant now, Instant leaseUntil, int limit) {
        return List.of();
    }

    default boolean update(RefundCommandResultModel expected, RefundCommandResultModel updated) {
        return false;
    }
}
