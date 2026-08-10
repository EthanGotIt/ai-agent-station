package cn.ethan.infrastructure.after_sales.gateway;

import cn.ethan.core.after_sales.enums.AfterSalesCaseStatusEnum;
import cn.ethan.core.after_sales.enums.AfterSalesHandlingModeEnum;
import cn.ethan.core.after_sales.enums.RefundReasonEnum;
import cn.ethan.core.after_sales.model.AfterSalesCaseModel;
import cn.ethan.core.after_sales.port.AfterSalesCaseGateway;
import cn.ethan.infrastructure.after_sales.entity.DemoAfterSalesCaseEntity;
import cn.ethan.infrastructure.after_sales.mapper.DemoAfterSalesCaseMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 本地售后申请网关：以用户和订单的唯一约束阻止重复退款申请。
 *
 * @author ethan
 * @date 2026-08-10
 */
@Component
public final class LocalAfterSalesCaseGateway implements AfterSalesCaseGateway {

    private static final String REFUND = "REFUND";

    private final DemoAfterSalesCaseMapper mapper;

    public LocalAfterSalesCaseGateway(DemoAfterSalesCaseMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<AfterSalesCaseModel> findByOrder(String orderId, String userId) {
        if (blank(orderId) || blank(userId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.selectOne(new LambdaQueryWrapper<DemoAfterSalesCaseEntity>()
                .eq(DemoAfterSalesCaseEntity::getOrderId, orderId)
                .eq(DemoAfterSalesCaseEntity::getUserId, userId)
                .eq(DemoAfterSalesCaseEntity::getRequestType, REFUND))).map(this::toModel);
    }

    @Override
    public Optional<AfterSalesCaseModel> findByWorkflowRunId(String workflowRunId) {
        return Optional.ofNullable(mapper.selectOne(new LambdaQueryWrapper<DemoAfterSalesCaseEntity>()
                .eq(DemoAfterSalesCaseEntity::getWorkflowRunId, workflowRunId))).map(this::toModel);
    }

    @Override
    public AfterSalesCaseModel create(AfterSalesCaseModel caseModel) {
        Optional<AfterSalesCaseModel> existing = findByWorkflowRunId(caseModel.workflowRunId());
        if (existing.isPresent()) {
            return existing.orElseThrow();
        }
        try {
            if (mapper.insert(toEntity(caseModel)) != 1) {
                throw new IllegalStateException("after-sales case was not created");
            }
            return caseModel;
        } catch (DuplicateKeyException duplicate) {
            return findByWorkflowRunId(caseModel.workflowRunId())
                    .or(() -> findByOrder(caseModel.orderId(), caseModel.userId()))
                    .orElseThrow(() -> duplicate);
        }
    }

    @Override
    public boolean update(AfterSalesCaseModel expected, AfterSalesCaseModel updated) {
        if (!expected.caseId().equals(updated.caseId()) || updated.version() != expected.version() + 1) {
            throw new IllegalArgumentException("after-sales case version transition is invalid");
        }
        return mapper.update(null, new LambdaUpdateWrapper<DemoAfterSalesCaseEntity>()
                .eq(DemoAfterSalesCaseEntity::getCaseId, expected.caseId())
                .eq(DemoAfterSalesCaseEntity::getVersion, expected.version())
                .set(DemoAfterSalesCaseEntity::getStatus, updated.status().name())
                .set(DemoAfterSalesCaseEntity::getRefundId, updated.refundId())
                .set(DemoAfterSalesCaseEntity::getVersion, updated.version())
                .set(DemoAfterSalesCaseEntity::getUpdatedAt, updated.updatedAt())) == 1;
    }

    private DemoAfterSalesCaseEntity toEntity(AfterSalesCaseModel model) {
        DemoAfterSalesCaseEntity entity = new DemoAfterSalesCaseEntity();
        entity.setCaseId(model.caseId());
        entity.setWorkflowRunId(model.workflowRunId());
        entity.setUserId(model.userId());
        entity.setOrderId(model.orderId());
        entity.setRequestType(REFUND);
        entity.setRefundReason(model.reason().name());
        entity.setDescription(model.description());
        entity.setHandlingMode(model.handlingMode().name());
        entity.setStatus(model.status().name());
        entity.setAmount(model.amount());
        entity.setCurrency(model.currency());
        entity.setRefundId(model.refundId());
        entity.setVersion(model.version());
        entity.setCreatedAt(model.createdAt());
        entity.setUpdatedAt(model.updatedAt());
        return entity;
    }

    private AfterSalesCaseModel toModel(DemoAfterSalesCaseEntity entity) {
        return new AfterSalesCaseModel(
                entity.getCaseId(), entity.getWorkflowRunId(), entity.getUserId(), entity.getOrderId(),
                RefundReasonEnum.valueOf(entity.getRefundReason()), entity.getDescription(),
                AfterSalesHandlingModeEnum.valueOf(entity.getHandlingMode()),
                AfterSalesCaseStatusEnum.valueOf(entity.getStatus()), entity.getAmount(), entity.getCurrency(),
                entity.getRefundId(), entity.getVersion(), entity.getCreatedAt(), entity.getUpdatedAt()
        );
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
