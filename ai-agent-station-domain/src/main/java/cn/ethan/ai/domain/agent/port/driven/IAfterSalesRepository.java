package cn.ethan.ai.domain.agent.port.driven;

import cn.ethan.ai.domain.agent.model.AfterSalesCaseView;
import cn.ethan.ai.domain.agent.model.AfterSalesOrderSnapshot;
import cn.ethan.ai.domain.agent.model.AfterSalesRefundResult;

import java.util.Optional;

public interface IAfterSalesRepository {

    Optional<AfterSalesOrderSnapshot> findOrder(String orderId, String requesterId);

    void createCase(String caseId, String userId, String sessionId, String message);

    void updateCase(AfterSalesCaseView caseView);

    Optional<AfterSalesCaseView> findCase(String caseId);

    boolean cancelCase(String caseId, String reason);

    default boolean tryAcquireResume(String caseId, String checkpointId, String resumeToken) {
        return true;
    }

    default void releaseResume(String caseId, String resumeToken) {
    }

    AfterSalesRefundResult executeRefund(String caseId, String orderId, String userId, String idempotencyKey);
}
