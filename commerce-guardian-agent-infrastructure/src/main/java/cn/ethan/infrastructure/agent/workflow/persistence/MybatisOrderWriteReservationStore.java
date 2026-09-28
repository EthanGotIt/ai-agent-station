package cn.ethan.infrastructure.agent.workflow.persistence;

import cn.ethan.core.agent.workflow.OrderWriteReservationStore;
import org.springframework.stereotype.Repository;

/**
 * 类型职责：把订单写事项预留绑定到 Workflow 的本地事务。
 *
 * @author ethan
 * @date 2026-09-28
 */
@Repository
public class MybatisOrderWriteReservationStore implements OrderWriteReservationStore {

    private final OrderWriteReservationMapper mapper;

    public MybatisOrderWriteReservationStore(OrderWriteReservationMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean reserve(String userId, String orderId, String runId) {
        if (mapper.reserve(userId, orderId, runId) == 1) return true;
        return runId.equals(mapper.holder(userId, orderId));
    }

    @Override
    public void release(String userId, String orderId, String runId) {
        mapper.release(userId, orderId, runId);
    }
}
