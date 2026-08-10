package cn.ethan.core.after_sales.port;

import cn.ethan.core.after_sales.model.AfterSalesCaseModel;

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

    AfterSalesCaseModel create(AfterSalesCaseModel caseModel);

    boolean update(AfterSalesCaseModel expected, AfterSalesCaseModel updated);
}
