package cn.ethan.ai.domain.agent.port.driven;

import cn.ethan.ai.domain.agent.model.AfterSalesOrderSnapshot;

import java.util.Optional;

public interface IOrderGateway {

    Optional<AfterSalesOrderSnapshot> findOrder(String orderId, String requesterId);
}
