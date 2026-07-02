package cn.ethan.ai.infrastructure.adapter.repository;

import cn.ethan.ai.domain.agent.adapter.repository.IAfterSalesRepository;
import cn.ethan.ai.domain.agent.model.AfterSalesCaseView;
import cn.ethan.ai.domain.agent.model.AfterSalesOrderSnapshot;
import cn.ethan.ai.domain.agent.model.AfterSalesRefundResult;
import cn.ethan.ai.domain.agent.model.valobj.enums.AfterSalesStage;
import cn.ethan.ai.infrastructure.dao.AfterSalesCaseMapper;
import cn.ethan.ai.infrastructure.dao.AfterSalesOutboxMapper;
import cn.ethan.ai.infrastructure.dao.DemoOrderMapper;
import cn.ethan.ai.infrastructure.dao.RefundCommandMapper;
import cn.ethan.ai.infrastructure.dao.po.AfterSalesCasePO;
import cn.ethan.ai.infrastructure.dao.po.AfterSalesOutboxPO;
import cn.ethan.ai.infrastructure.dao.po.DemoOrderPO;
import cn.ethan.ai.infrastructure.dao.po.RefundCommandPO;
import com.alibaba.fastjson.JSON;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AfterSalesRepository implements IAfterSalesRepository {

    private final DemoOrderMapper demoOrderMapper;
    private final AfterSalesCaseMapper afterSalesCaseMapper;
    private final RefundCommandMapper refundCommandMapper;
    private final AfterSalesOutboxMapper afterSalesOutboxMapper;
    private final TransactionTemplate transactionTemplate;

    public AfterSalesRepository(DemoOrderMapper demoOrderMapper,
                                AfterSalesCaseMapper afterSalesCaseMapper,
                                RefundCommandMapper refundCommandMapper,
                                AfterSalesOutboxMapper afterSalesOutboxMapper,
                                @Qualifier("mysqlTransactionTemplate") TransactionTemplate transactionTemplate) {
        this.demoOrderMapper = demoOrderMapper;
        this.afterSalesCaseMapper = afterSalesCaseMapper;
        this.refundCommandMapper = refundCommandMapper;
        this.afterSalesOutboxMapper = afterSalesOutboxMapper;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public Optional<AfterSalesOrderSnapshot> findOrder(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return Optional.empty();
        }
        DemoOrderPO order = demoOrderMapper.selectByOrderId(orderId);
        if (order == null) {
            return Optional.empty();
        }
        return Optional.of(new AfterSalesOrderSnapshot(
                order.getOrderId(),
                order.getUserId(),
                order.getStatus(),
                order.getDaysSinceDelivery()
        ));
    }

    @Override
    public void createCase(String runId, String caseId, String userId, String sessionId, String message) {
        afterSalesCaseMapper.insert(AfterSalesCasePO.builder()
                .caseId(caseId)
                .runId(runId)
                .userId(userId)
                .sessionId(sessionId)
                .userMessage(message)
                .stage(AfterSalesStage.INTAKE.name())
                .build());
    }

    @Override
    public void updateCase(AfterSalesCaseView caseView) {
        afterSalesCaseMapper.updateByRunId(AfterSalesCasePO.builder()
                .runId(caseView.runId())
                .orderId(caseView.orderId())
                .stage(caseView.stage())
                .checkpointId(caseView.checkpointId())
                .nextNode(caseView.nextNode())
                .terminalReason(caseView.terminalReason())
                .commandId(caseView.commandId())
                .build());
    }

    @Override
    public Optional<AfterSalesCaseView> findCase(String runId) {
        AfterSalesCasePO afterSalesCase = afterSalesCaseMapper.selectByRunId(runId);
        if (afterSalesCase == null) {
            return Optional.empty();
        }
        return Optional.of(new AfterSalesCaseView(
                afterSalesCase.getRunId(),
                afterSalesCase.getCaseId(),
                afterSalesCase.getUserId(),
                afterSalesCase.getSessionId(),
                afterSalesCase.getOrderId(),
                afterSalesCase.getStage(),
                afterSalesCase.getCheckpointId(),
                afterSalesCase.getNextNode(),
                afterSalesCase.getTerminalReason(),
                afterSalesCase.getCommandId()
        ));
    }

    @Override
    public boolean cancelCase(String runId, String reason) {
        return afterSalesCaseMapper.cancelByRunId(runId, reason) > 0;
    }

    @Override
    public AfterSalesRefundResult executeRefund(String caseId,
                                                String orderId,
                                                String userId,
                                                String idempotencyKey) {
        AfterSalesRefundResult result = transactionTemplate.execute(status ->
                executeRefundInTransaction(caseId, orderId, userId, idempotencyKey));
        if (result == null) {
            throw new IllegalStateException("退款事务没有返回结果");
        }
        return result;
    }

    private AfterSalesRefundResult executeRefundInTransaction(String caseId,
                                                              String orderId,
                                                              String userId,
                                                              String idempotencyKey) {
        Optional<RefundCommandPO> existing = findRefundCommand(idempotencyKey);
        if (existing.isPresent() && "SUCCESS".equals(existing.get().getStatus())) {
            return new AfterSalesRefundResult(true, true, existing.get().getCommandId(), "ALREADY_EXECUTED");
        }

        String commandId = existing.map(RefundCommandPO::getCommandId).orElse(UUID.randomUUID().toString());
        if (existing.isEmpty()) {
            int inserted = refundCommandMapper.insertIgnore(RefundCommandPO.builder()
                    .commandId(commandId)
                    .caseId(caseId)
                    .orderId(orderId)
                    .userId(userId)
                    .idempotencyKey(idempotencyKey)
                    .status("PENDING")
                    .build());
            if (inserted == 0) {
                RefundCommandPO concurrent = findRefundCommand(idempotencyKey)
                        .orElseThrow(() -> new IllegalStateException("幂等退款命令并发创建后不可见"));
                return "SUCCESS".equals(concurrent.getStatus())
                        ? new AfterSalesRefundResult(true, true, concurrent.getCommandId(), "ALREADY_EXECUTED")
                        : new AfterSalesRefundResult(false, true, concurrent.getCommandId(), "COMMAND_IN_PROGRESS");
            }
        }

        int changed = demoOrderMapper.updateStatusForRefund(orderId, userId);
        if (changed == 0) {
            Optional<AfterSalesOrderSnapshot> order = findOrder(orderId);
            if (order.isPresent() && userId.equals(order.get().ownerId())
                    && "REFUNDED".equalsIgnoreCase(order.get().status())) {
                refundCommandMapper.markSuccess(commandId);
                return new AfterSalesRefundResult(true, true, commandId, "ALREADY_REFUNDED");
            }
            refundCommandMapper.markFailed(commandId, "ORDER_STATE_CONFLICT");
            return new AfterSalesRefundResult(false, false, commandId, "ORDER_STATE_CONFLICT");
        }

        refundCommandMapper.markSuccess(commandId);
        afterSalesCaseMapper.markRefundExecuting(caseId, commandId);
        String eventId = UUID.nameUUIDFromBytes(
                (commandId + ":REFUND_SUCCEEDED").getBytes(StandardCharsets.UTF_8)
        ).toString();
        afterSalesOutboxMapper.insertIgnore(AfterSalesOutboxPO.builder()
                .eventId(eventId)
                .aggregateId(caseId)
                .eventType("REFUND_SUCCEEDED")
                .payload(JSON.toJSONString(Map.of(
                        "caseId", caseId,
                        "commandId", commandId,
                        "orderId", orderId,
                        "occurredAt", Timestamp.valueOf(LocalDateTime.now()).toString()
                )))
                .status("PENDING")
                .build());
        return new AfterSalesRefundResult(true, false, commandId, "REFUND_EXECUTED");
    }

    private Optional<RefundCommandPO> findRefundCommand(String idempotencyKey) {
        return Optional.ofNullable(refundCommandMapper.selectByIdempotencyKeyForUpdate(idempotencyKey));
    }
}
