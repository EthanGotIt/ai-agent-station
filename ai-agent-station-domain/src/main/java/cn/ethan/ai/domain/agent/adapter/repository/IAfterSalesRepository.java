package cn.ethan.ai.domain.agent.adapter.repository;

import cn.ethan.ai.domain.agent.model.AfterSalesCaseView;
import cn.ethan.ai.domain.agent.model.AfterSalesOrderSnapshot;
import cn.ethan.ai.domain.agent.model.AfterSalesRefundResult;

import java.util.Optional;

public interface IAfterSalesRepository {

    Optional<AfterSalesOrderSnapshot> findOrder(String orderId);

    void createCase(String runId, String caseId, String userId, String sessionId, String message);

    void updateCase(AfterSalesCaseView caseView);

    Optional<AfterSalesCaseView> findCase(String runId);

    boolean cancelCase(String runId, String reason);

    AfterSalesRefundResult executeRefund(String caseId, String orderId, String userId, String idempotencyKey);
}
