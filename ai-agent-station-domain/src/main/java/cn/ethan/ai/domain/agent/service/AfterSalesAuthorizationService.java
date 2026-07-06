package cn.ethan.ai.domain.agent.service;

import cn.ethan.ai.domain.agent.exception.AfterSalesResumeConflictException;
import cn.ethan.ai.domain.agent.model.AfterSalesCaseView;
import cn.ethan.ai.domain.agent.model.AfterSalesResumeCommand;
import cn.ethan.ai.domain.agent.model.valobj.enums.AfterSalesStage;

/**
 * 售后Agent权限服务，负责Case访问与恢复授权检查。
 */
public final class AfterSalesAuthorizationService {

    public void authorizeResume(AfterSalesCaseView caseView, AfterSalesResumeCommand command) {
        if (command.action() == AfterSalesResumeCommand.ResumeAction.SUPPLY_INFO) {
            assert caseView.userIdValue() != null;
            if (!caseView.userIdValue().equals(command.actorIdValue())) {
                throw new SecurityException("只有Case 所有者可以补充信息");
            }
            return;
        }
        if (!"AFTER_SALES_APPROVER".equalsIgnoreCase(command.actorRole())) {
            throw new SecurityException("退款审批需要 AFTER_SALES_APPROVER 角色");
        }
    }

    public boolean canAccess(AfterSalesCaseView caseView, String requesterId, String requesterRole) {
        assert caseView.userIdValue() != null;
        return caseView.userIdValue().equals(requesterId)
                || "AFTER_SALES_APPROVER".equalsIgnoreCase(requesterRole);
    }

    public void assertResumable(AfterSalesCaseView caseView) {
        if (AfterSalesStage.COMPLETED.name().equals(caseView.stage())
                || AfterSalesStage.REJECTED.name().equals(caseView.stage())) {
            throw new AfterSalesResumeConflictException("售后Case已结束，不能继续恢复");
        }
    }
}
