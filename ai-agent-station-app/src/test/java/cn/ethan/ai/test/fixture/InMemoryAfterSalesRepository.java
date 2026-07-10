package cn.ethan.ai.test.fixture;

import cn.ethan.ai.domain.agent.model.AfterSalesCaseView;
import cn.ethan.ai.domain.agent.model.AfterSalesOrderSnapshot;
import cn.ethan.ai.domain.agent.model.AfterSalesRefundResult;
import cn.ethan.ai.domain.agent.model.valobj.enums.AfterSalesStage;
import cn.ethan.ai.domain.agent.port.driven.IAfterSalesRepository;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 共享的内存版售后仓库，供单元测试使用。
 */
public final class InMemoryAfterSalesRepository implements IAfterSalesRepository {

    public final Map<String, AfterSalesOrderSnapshot> orders = new ConcurrentHashMap<>();
    private final Map<String, AfterSalesCaseView> cases = new ConcurrentHashMap<>();
    private final Map<String, AfterSalesRefundResult> commands = new ConcurrentHashMap<>();
    private final Map<String, ResumeLease> resumeLocks = new ConcurrentHashMap<>();
    public final AtomicInteger refundExecutions = new AtomicInteger();

    @Override
    public Optional<AfterSalesOrderSnapshot> findOrder(String orderId, String requesterId) {
        return Optional.ofNullable(orders.get(orderId));
    }

    @Override
    public void createCase(String caseId, String userId, String sessionId, String message) {
        cases.put(caseId, AfterSalesCaseView.of(caseId, userId, sessionId,
                null, AfterSalesStage.INTAKE.name(), null, null, null, null));
    }

    @Override
    public void updateCase(AfterSalesCaseView caseView) {
        cases.put(caseView.caseIdValue(), caseView);
        resumeLocks.remove(caseView.caseIdValue());
    }

    @Override
    public Optional<AfterSalesCaseView> findCase(String caseId) {
        return Optional.ofNullable(cases.get(caseId));
    }

    @Override
    public boolean cancelCase(String caseId, String reason) {
        return cases.containsKey(caseId);
    }

    @Override
    public synchronized boolean tryAcquireResume(String caseId,
                                                 String checkpointId,
                                                 String resumeToken,
                                                 long leaseSeconds) {
        AfterSalesCaseView current = cases.get(caseId);
        if (current == null || !checkpointId.equals(current.checkpointId())) {
            return false;
        }
        ResumeLease existing = resumeLocks.get(caseId);
        if (existing != null && existing.lockedUntil().isAfter(LocalDateTime.now())) {
            return false;
        }
        resumeLocks.put(caseId, new ResumeLease(resumeToken, LocalDateTime.now().plusSeconds(leaseSeconds)));
        return true;
    }

    @Override
    public synchronized void releaseResume(String caseId, String resumeToken) {
        ResumeLease existing = resumeLocks.get(caseId);
        if (existing != null && existing.token().equals(resumeToken)) {
            resumeLocks.remove(caseId);
        }
    }

    public synchronized void expireResumeLease(String caseId) {
        ResumeLease existing = resumeLocks.get(caseId);
        if (existing != null) {
            resumeLocks.put(caseId, new ResumeLease(existing.token(), LocalDateTime.now().minusSeconds(1)));
        }
    }

    @Override
    public synchronized AfterSalesRefundResult executeRefund(String caseId, String orderId,
                                                             String userId, String idempotencyKey) {
        AfterSalesRefundResult existing = commands.get(idempotencyKey);
        if (existing != null) {
            return new AfterSalesRefundResult(true, true, existing.commandId(), "ALREADY_EXECUTED");
        }
        AfterSalesOrderSnapshot order = orders.get(orderId);
        if (order == null || !userId.equals(order.ownerId())) {
            return new AfterSalesRefundResult(false, false, null, "ORDER_STATE_CONFLICT");
        }
        String commandId = UUID.randomUUID().toString();
        refundExecutions.incrementAndGet();
        orders.put(orderId, new AfterSalesOrderSnapshot(orderId, userId, "REFUNDED",
                order.daysSinceDelivery()));
        AfterSalesRefundResult result = new AfterSalesRefundResult(
                true, false, commandId, "REFUND_EXECUTED");
        commands.put(idempotencyKey, result);
        return result;
    }

    private record ResumeLease(String token, LocalDateTime lockedUntil) {
    }
}
