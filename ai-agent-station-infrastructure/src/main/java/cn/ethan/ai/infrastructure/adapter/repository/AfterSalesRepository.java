package cn.ethan.ai.infrastructure.adapter.repository;

import cn.ethan.ai.domain.agent.port.driven.IAfterSalesRepository;
import cn.ethan.ai.domain.agent.port.driven.IOrderGateway;
import cn.ethan.ai.domain.agent.port.driven.IRefundGateway;
import cn.ethan.ai.domain.agent.model.AfterSalesCaseView;
import cn.ethan.ai.domain.agent.model.AfterSalesOrderSnapshot;
import cn.ethan.ai.domain.agent.model.AfterSalesRefundResult;
import cn.ethan.ai.domain.agent.model.RefundGatewayResult;
import cn.ethan.ai.domain.agent.model.valobj.enums.AfterSalesStage;
import cn.ethan.ai.infrastructure.dao.AfterSalesCaseMapper;
import cn.ethan.ai.infrastructure.dao.AfterSalesOutboxMapper;
import cn.ethan.ai.infrastructure.dao.RefundCommandMapper;
import cn.ethan.ai.infrastructure.dao.po.AfterSalesCasePO;
import cn.ethan.ai.infrastructure.dao.po.AfterSalesOutboxPO;
import cn.ethan.ai.infrastructure.dao.po.RefundCommandPO;
import cn.ethan.ai.infrastructure.observability.AfterSalesRuntimeMetrics;
import cn.ethan.ai.infrastructure.json.AfterSalesJsonCodec;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
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

    private final IOrderGateway orderGateway;
    private final IRefundGateway refundGateway;
    private final AfterSalesCaseMapper afterSalesCaseMapper;
    private final RefundCommandMapper refundCommandMapper;
    private final AfterSalesOutboxMapper afterSalesOutboxMapper;
    private final TransactionTemplate transactionTemplate;
    private final AfterSalesRuntimeMetrics metrics;
    private final AfterSalesJsonCodec jsonCodec;

    @Autowired
    public AfterSalesRepository(IOrderGateway orderGateway,
                                IRefundGateway refundGateway,
                                AfterSalesCaseMapper afterSalesCaseMapper,
                                RefundCommandMapper refundCommandMapper,
                                AfterSalesOutboxMapper afterSalesOutboxMapper,
                                @Qualifier("mysqlTransactionTemplate") TransactionTemplate transactionTemplate,
                                AfterSalesRuntimeMetrics metrics,
                                AfterSalesJsonCodec jsonCodec) {
        this.orderGateway = orderGateway;
        this.refundGateway = refundGateway;
        this.afterSalesCaseMapper = afterSalesCaseMapper;
        this.refundCommandMapper = refundCommandMapper;
        this.afterSalesOutboxMapper = afterSalesOutboxMapper;
        this.transactionTemplate = transactionTemplate;
        this.metrics = metrics;
        this.jsonCodec = jsonCodec;
    }

    public AfterSalesRepository(IOrderGateway orderGateway,
                                IRefundGateway refundGateway,
                                AfterSalesCaseMapper afterSalesCaseMapper,
                                RefundCommandMapper refundCommandMapper,
                                AfterSalesOutboxMapper afterSalesOutboxMapper,
                                TransactionTemplate transactionTemplate) {
        this(orderGateway, refundGateway, afterSalesCaseMapper, refundCommandMapper,
                afterSalesOutboxMapper, transactionTemplate, AfterSalesRuntimeMetrics.noop(),
                AfterSalesJsonCodec.defaultCodec());
    }

    @Override
    public Optional<AfterSalesOrderSnapshot> findOrder(String orderId, String requesterId) {
        return orderGateway.findOrder(orderId, requesterId);
    }

    @Override
    public void createCase(String caseId, String userId, String sessionId, String message) {
        afterSalesCaseMapper.insert(AfterSalesCasePO.builder()
                .caseId(caseId)
                .userId(userId)
                .sessionId(sessionId)
                .userMessage(message)
                .stage(AfterSalesStage.INTAKE.name())
                .build());
    }

    @Override
    public void updateCase(AfterSalesCaseView caseView) {
        afterSalesCaseMapper.updateByCaseId(AfterSalesCasePO.builder()
                .caseId(caseView.caseIdValue())
                .orderId(caseView.orderIdValue())
                .stage(caseView.stage())
                .checkpointId(caseView.checkpointId())
                .nextNode(caseView.nextNode())
                .terminalReason(caseView.terminalReason())
                .commandId(caseView.commandId())
                .build());
    }

    @Override
    public Optional<AfterSalesCaseView> findCase(String caseId) {
        AfterSalesCasePO afterSalesCase = afterSalesCaseMapper.selectByCaseId(caseId);
        if (afterSalesCase == null) {
            return Optional.empty();
        }
        return Optional.of(AfterSalesCaseView.of(
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
    public boolean cancelCase(String caseId, String reason) {
        return afterSalesCaseMapper.cancelByCaseId(caseId, reason) > 0;
    }

    @Override
    public boolean tryAcquireResume(String caseId,
                                    String checkpointId,
                                    String resumeToken,
                                    long leaseSeconds) {
        boolean acquired = afterSalesCaseMapper.tryAcquireResume(
                caseId, checkpointId, resumeToken, leaseSeconds) > 0;
        metrics.recordResumeAcquire(acquired);
        return acquired;
    }

    @Override
    public void releaseResume(String caseId, String resumeToken) {
        afterSalesCaseMapper.releaseResume(caseId, resumeToken);
    }

    @Override
    public AfterSalesRefundResult executeRefund(String caseId,
                                                String orderId,
                                                String userId,
                                                String idempotencyKey) {
        try {
            AfterSalesRefundResult result = doExecuteRefund(caseId, orderId, userId, idempotencyKey);
            metrics.recordRefund(result.success() ? "success" : "rejected");
            return result;
        } catch (RuntimeException error) {
            metrics.recordRefund("error");
            throw error;
        }
    }

    private AfterSalesRefundResult doExecuteRefund(String caseId,
                                                   String orderId,
                                                   String userId,
                                                   String idempotencyKey) {
        PreparedRefund prepared = transactionTemplate.execute(status ->
                prepareRefund(caseId, orderId, userId, idempotencyKey));
        if (prepared == null) {
            throw new IllegalStateException("退款命令准备事务没有返回结果");
        }
        if (prepared.terminalResult() != null) {
            return prepared.terminalResult();
        }

        RefundGatewayResult gatewayResult;
        try {
            gatewayResult = refundGateway.executeRefund(orderId, userId, idempotencyKey);
        } catch (RuntimeException error) {
            transactionTemplate.executeWithoutResult(status -> refundCommandMapper.markFailed(
                    prepared.commandId(), "GATEWAY_ERROR:" + error.getClass().getSimpleName()));
            throw error;
        }

        AfterSalesRefundResult result = transactionTemplate.execute(status ->
                finalizeRefund(caseId, orderId, prepared.commandId(), gatewayResult));
        if (result == null) {
            throw new IllegalStateException("退款确认事务没有返回结果");
        }
        return result;
    }

    private PreparedRefund prepareRefund(String caseId,
                                         String orderId,
                                         String userId,
                                         String idempotencyKey) {
        Optional<RefundCommandPO> existing = findRefundCommand(idempotencyKey);
        if (existing.isPresent() && "SUCCESS".equals(existing.get().getStatus())) {
            return new PreparedRefund(existing.get().getCommandId(),
                    new AfterSalesRefundResult(true, true, existing.get().getCommandId(), "ALREADY_EXECUTED"));
        }
        if (existing.isPresent() && "PENDING".equals(existing.get().getStatus())) {
            return new PreparedRefund(existing.get().getCommandId(),
                    new AfterSalesRefundResult(false, true, existing.get().getCommandId(), "COMMAND_IN_PROGRESS"));
        }
        if (existing.isPresent() && "FAILED".equals(existing.get().getStatus())) {
            refundCommandMapper.markPending(existing.get().getCommandId());
            return new PreparedRefund(existing.get().getCommandId(), null);
        }

        String commandId = UUID.randomUUID().toString();
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
            AfterSalesRefundResult concurrentResult = "SUCCESS".equals(concurrent.getStatus())
                    ? new AfterSalesRefundResult(true, true, concurrent.getCommandId(), "ALREADY_EXECUTED")
                    : new AfterSalesRefundResult(false, true, concurrent.getCommandId(), "COMMAND_IN_PROGRESS");
            return new PreparedRefund(concurrent.getCommandId(), concurrentResult);
        }
        return new PreparedRefund(commandId, null);
    }

    private AfterSalesRefundResult finalizeRefund(String caseId,
                                                  String orderId,
                                                  String commandId,
                                                  RefundGatewayResult gatewayResult) {
        if (!gatewayResult.success()) {
            refundCommandMapper.markFailed(commandId, gatewayResult.reason());
            return new AfterSalesRefundResult(false, gatewayResult.idempotentReplay(),
                    commandId, gatewayResult.reason());
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
                .payload(jsonCodec.write(Map.of(
                        "caseId", caseId,
                        "commandId", commandId,
                        "orderId", orderId,
                        "occurredAt", Timestamp.valueOf(LocalDateTime.now()).toString()
                ), "序列化退款成功事件"))
                .status("PENDING")
                .build());
        return new AfterSalesRefundResult(true, gatewayResult.idempotentReplay(), commandId, gatewayResult.reason());
    }

    private Optional<RefundCommandPO> findRefundCommand(String idempotencyKey) {
        return Optional.ofNullable(refundCommandMapper.selectByIdempotencyKeyForUpdate(idempotencyKey));
    }

    private record PreparedRefund(String commandId, AfterSalesRefundResult terminalResult) {
    }
}
