package cn.ethan.ai.domain.agent.port.driven;

import cn.ethan.ai.domain.agent.model.AfterSalesOrderSnapshot;
import cn.ethan.ai.domain.agent.model.AfterSalesLogisticsSnapshot;
import cn.ethan.ai.domain.agent.model.AfterSalesRefundHistorySnapshot;

import java.util.Optional;

public interface IOrderGateway {

    Optional<AfterSalesOrderSnapshot> findOrder(String orderId, String requesterId);

    default Optional<AfterSalesLogisticsSnapshot> findLogistics(String orderId, String requesterId) {
        return Optional.empty();
    }

    default Optional<AfterSalesRefundHistorySnapshot> findRefundHistory(String orderId, String requesterId) {
        return Optional.empty();
    }
}
