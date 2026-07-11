package cn.ethan.ai.test.fixture;

import cn.ethan.ai.domain.agent.model.AfterSalesCaseView;
import cn.ethan.ai.domain.agent.model.AfterSalesOrderSnapshot;
import cn.ethan.ai.domain.agent.model.AfterSalesRefundResult;
import cn.ethan.ai.domain.agent.port.driven.IAfterSalesRepository;

import java.util.Optional;

/**
 * 仅供局部测试桩继承，防止未参与测试的仓库方法被静默调用。
 */
public abstract class UnsupportedAfterSalesRepository implements IAfterSalesRepository {

    @Override
    public Optional<AfterSalesOrderSnapshot> findOrder(String orderId, String requesterId) {
        throw unsupported();
    }

    @Override
    public void createCase(String caseId, String userId, String sessionId, String message) {
        throw unsupported();
    }

    @Override
    public void updateCase(AfterSalesCaseView caseView) {
        throw unsupported();
    }

    @Override
    public Optional<AfterSalesCaseView> findCase(String caseId) {
        throw unsupported();
    }

    @Override
    public boolean cancelCase(String caseId, String reason) {
        throw unsupported();
    }

    @Override
    public boolean tryAcquireResume(String caseId, String checkpointId, String resumeToken, long leaseSeconds) {
        throw unsupported();
    }

    @Override
    public void releaseResume(String caseId, String resumeToken) {
        throw unsupported();
    }

    @Override
    public AfterSalesRefundResult executeRefund(String caseId, String orderId, String userId, String idempotencyKey) {
        throw unsupported();
    }

    private UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("This repository operation is not part of the test scenario");
    }
}
