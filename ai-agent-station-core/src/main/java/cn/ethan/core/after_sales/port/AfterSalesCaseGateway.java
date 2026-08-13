package cn.ethan.core.after_sales.port;

import cn.ethan.core.after_sales.model.AfterSalesCaseModel;
import cn.ethan.core.after_sales.enums.AfterSalesCaseStatusEnum;

import java.util.List;
import java.util.Optional;

/**
 * 售后申请网关：集中处理按订单去重的本地申请单事实。
 *
 * @author ethan
 * @date 2026-08-10
 */
public interface AfterSalesCaseGateway {

    Optional<AfterSalesCaseModel> findByOrder(String orderId, String userId);

    Optional<AfterSalesCaseModel> findByWorkflowRunId(String workflowRunId);

    default Optional<AfterSalesCaseModel> findByCaseId(String caseId) {
        return Optional.empty();
    }

    default List<AfterSalesCaseModel> findPage(
            AfterSalesCaseStatusEnum status,
            int offset,
            int limit
    ) {
        return List.of();
    }

    AfterSalesCaseModel create(AfterSalesCaseModel caseModel);

    boolean update(AfterSalesCaseModel expected, AfterSalesCaseModel updated);
}
