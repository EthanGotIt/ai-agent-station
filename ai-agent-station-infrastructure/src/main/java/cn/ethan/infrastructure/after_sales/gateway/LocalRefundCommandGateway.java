package cn.ethan.infrastructure.after_sales.gateway;

import cn.ethan.core.after_sales.enums.RefundCommandStatusEnum;
import cn.ethan.core.after_sales.model.RefundCommandModel;
import cn.ethan.core.after_sales.model.RefundCommandResultModel;
import cn.ethan.core.after_sales.port.RefundCommandGateway;
import cn.ethan.infrastructure.after_sales.entity.DemoRefundCommandEntity;
import cn.ethan.infrastructure.after_sales.mapper.DemoRefundCommandMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 本地退款命令网关：用唯一 WorkflowRun 键模拟可重试且幂等的退款写入。
 *
 * @author ethan
 * @date 2026-08-07
 */
@Component
public final class LocalRefundCommandGateway implements RefundCommandGateway {

    private final DemoRefundCommandMapper mapper;

    public LocalRefundCommandGateway(DemoRefundCommandMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public RefundCommandResultModel create(RefundCommandModel command) {
        Optional<DemoRefundCommandEntity> existing = findByRunId(command.workflowRunId());
        if (existing.isPresent()) {
            return toModel(existing.orElseThrow());
        }
        Optional<RefundCommandResultModel> existingCase = findByCaseId(command.caseId());
        if (existingCase.isPresent()) {
            return existingCase.orElseThrow();
        }

        DemoRefundCommandEntity entity = new DemoRefundCommandEntity();
        entity.setRefundId("REFUND-" + UUID.randomUUID());
        entity.setWorkflowRunId(command.workflowRunId());
        entity.setCaseId(command.caseId());
        entity.setOrderId(command.orderId());
        entity.setUserId(command.userId());
        entity.setRefundReason(command.reason().name());
        entity.setAmount(command.amount());
        entity.setCurrency(command.currency());
        entity.setStatus(RefundCommandStatusEnum.PENDING.name());
        entity.setRetryId("");
        entity.setAttemptCount(0);
        entity.setNextAttemptAt(command.createdAt());
        entity.setLeaseUntil(null);
        entity.setFailureCode("");
        entity.setVersion(0L);
        entity.setCreatedAt(command.createdAt());
        entity.setUpdatedAt(command.createdAt());
        try {
            if (mapper.insert(entity) != 1) {
                throw new IllegalStateException("refund command was not created");
            }
            return toModel(entity);
        } catch (DuplicateKeyException duplicate) {
            return findByRunId(command.workflowRunId())
                    .map(this::toModel)
                    .or(() -> findByCaseId(command.caseId()))
                    .orElseThrow(() -> duplicate);
        }
    }

    @Override
    public Optional<RefundCommandResultModel> findByOrder(String orderId, String userId) {
        DemoRefundCommandEntity entity = mapper.selectOne(
                new LambdaQueryWrapper<DemoRefundCommandEntity>()
                        .eq(DemoRefundCommandEntity::getOrderId, orderId)
                        .eq(DemoRefundCommandEntity::getUserId, userId)
                        .orderByDesc(DemoRefundCommandEntity::getCreatedAt)
                        .last("LIMIT 1")
        );
        return Optional.ofNullable(entity).map(this::toModel);
    }

    @Override
    public Optional<RefundCommandResultModel> findByCaseId(String caseId) {
        if (caseId == null || caseId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.selectOne(new LambdaQueryWrapper<DemoRefundCommandEntity>()
                .eq(DemoRefundCommandEntity::getCaseId, caseId))).map(this::toModel);
    }

    @Override
    public List<RefundCommandResultModel> claimDue(Instant now, Instant leaseUntil, int limit) {
        if (now == null || leaseUntil == null || !leaseUntil.isAfter(now) || limit < 1) {
            return List.of();
        }
        int safeLimit = Math.min(limit, 100);
        LambdaQueryWrapper<DemoRefundCommandEntity> query = new LambdaQueryWrapper<DemoRefundCommandEntity>()
                .and(wrapper -> wrapper
                        .nested(due -> due.in(DemoRefundCommandEntity::getStatus,
                                        RefundCommandStatusEnum.PENDING.name(), RefundCommandStatusEnum.RETRY_WAIT.name())
                                .le(DemoRefundCommandEntity::getNextAttemptAt, now))
                        .or(expired -> expired.eq(DemoRefundCommandEntity::getStatus,
                                        RefundCommandStatusEnum.PROCESSING.name())
                                .le(DemoRefundCommandEntity::getLeaseUntil, now)))
                .orderByAsc(DemoRefundCommandEntity::getNextAttemptAt)
                .last("LIMIT " + safeLimit);
        List<RefundCommandResultModel> claimed = new ArrayList<>();
        for (DemoRefundCommandEntity entity : mapper.selectList(query)) {
            RefundCommandResultModel current = toModel(entity);
            RefundCommandResultModel next;
            try {
                next = current.claimed(leaseUntil, now);
            } catch (IllegalStateException notClaimable) {
                continue;
            }
            if (update(current, next)) {
                claimed.add(next);
            }
        }
        return List.copyOf(claimed);
    }

    @Override
    public boolean update(RefundCommandResultModel expected, RefundCommandResultModel updated) {
        if (expected == null || updated == null || !expected.refundId().equals(updated.refundId())
                || updated.version() != expected.version() + 1) {
            throw new IllegalArgumentException("refund command version transition is invalid");
        }
        return mapper.update(null, new LambdaUpdateWrapper<DemoRefundCommandEntity>()
                .eq(DemoRefundCommandEntity::getRefundId, expected.refundId())
                .eq(DemoRefundCommandEntity::getVersion, expected.version())
                .set(DemoRefundCommandEntity::getStatus, updated.status())
                .set(DemoRefundCommandEntity::getRetryId, updated.retryId())
                .set(DemoRefundCommandEntity::getAttemptCount, updated.attemptCount())
                .set(DemoRefundCommandEntity::getNextAttemptAt, updated.nextAttemptAt())
                .set(DemoRefundCommandEntity::getLeaseUntil, updated.leaseUntil())
                .set(DemoRefundCommandEntity::getFailureCode, updated.failureCode())
                .set(DemoRefundCommandEntity::getVersion, updated.version())
                .set(DemoRefundCommandEntity::getUpdatedAt, updated.updatedAt())) == 1;
    }

    private Optional<DemoRefundCommandEntity> findByRunId(String runId) {
        return Optional.ofNullable(mapper.selectOne(
                new LambdaQueryWrapper<DemoRefundCommandEntity>()
                        .eq(DemoRefundCommandEntity::getWorkflowRunId, runId)
        ));
    }

    private RefundCommandResultModel toModel(DemoRefundCommandEntity entity) {
        return new RefundCommandResultModel(
                entity.getRefundId(),
                entity.getCaseId(), entity.getWorkflowRunId(), entity.getOrderId(), entity.getUserId(),
                normalizeStatus(entity.getStatus()), entity.getAmount(), entity.getCurrency(),
                entity.getRetryId(), entity.getAttemptCount() == null ? 0 : entity.getAttemptCount(),
                entity.getNextAttemptAt() == null ? entity.getCreatedAt() : entity.getNextAttemptAt(),
                entity.getLeaseUntil(), entity.getFailureCode(), entity.getVersion() == null ? 0 : entity.getVersion(),
                entity.getCreatedAt(), entity.getUpdatedAt() == null ? entity.getCreatedAt() : entity.getUpdatedAt()
        );
    }

    private String normalizeStatus(String status) {
        return "ACCEPTED".equals(status) ? RefundCommandStatusEnum.PENDING.name() : status;
    }
}
