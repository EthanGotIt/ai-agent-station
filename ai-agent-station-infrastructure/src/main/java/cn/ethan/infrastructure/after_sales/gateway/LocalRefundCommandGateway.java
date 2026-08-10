package cn.ethan.infrastructure.after_sales.gateway;

import cn.ethan.core.after_sales.model.RefundCommandModel;
import cn.ethan.core.after_sales.model.RefundCommandResultModel;
import cn.ethan.core.after_sales.port.RefundCommandGateway;
import cn.ethan.infrastructure.after_sales.entity.DemoRefundCommandEntity;
import cn.ethan.infrastructure.after_sales.mapper.DemoRefundCommandMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;
import org.springframework.dao.DuplicateKeyException;

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

    private static final String ACCEPTED = "ACCEPTED";

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

        DemoRefundCommandEntity entity = new DemoRefundCommandEntity();
        entity.setRefundId("REFUND-" + UUID.randomUUID());
        entity.setWorkflowRunId(command.workflowRunId());
        entity.setCaseId(command.caseId());
        entity.setOrderId(command.orderId());
        entity.setUserId(command.userId());
        entity.setRefundReason(command.reason().name());
        entity.setAmount(command.amount());
        entity.setCurrency(command.currency());
        entity.setStatus(ACCEPTED);
        entity.setCreatedAt(command.createdAt());
        try {
            if (mapper.insert(entity) != 1) {
                throw new IllegalStateException("refund command was not created");
            }
            return toModel(entity);
        } catch (DuplicateKeyException duplicate) {
            return findByRunId(command.workflowRunId())
                    .map(this::toModel)
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

    private Optional<DemoRefundCommandEntity> findByRunId(String runId) {
        return Optional.ofNullable(mapper.selectOne(
                new LambdaQueryWrapper<DemoRefundCommandEntity>()
                        .eq(DemoRefundCommandEntity::getWorkflowRunId, runId)
        ));
    }

    private RefundCommandResultModel toModel(DemoRefundCommandEntity entity) {
        return new RefundCommandResultModel(
                entity.getRefundId(),
                entity.getCaseId() == null || entity.getCaseId().isBlank()
                        ? entity.getWorkflowRunId() : entity.getCaseId(),
                entity.getOrderId(),
                entity.getUserId(),
                entity.getStatus(),
                entity.getAmount(),
                entity.getCurrency(),
                entity.getCreatedAt()
        );
    }
}
