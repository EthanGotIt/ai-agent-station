package cn.ethan.infrastructure.after_sales.manager;

import cn.ethan.core.after_sales.exception.AfterSalesCaseConflictException;
import cn.ethan.core.after_sales.model.AfterSalesCaseModel;
import cn.ethan.core.after_sales.model.AfterSalesRefundSubmissionResultModel;
import cn.ethan.core.after_sales.model.RefundCommandModel;
import cn.ethan.core.after_sales.model.RefundCommandResultModel;
import cn.ethan.core.after_sales.port.AfterSalesCaseGateway;
import cn.ethan.core.after_sales.port.AfterSalesRefundSubmissionGateway;
import cn.ethan.core.after_sales.port.RefundCommandGateway;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 自动退款提交事务管理器：原子写入申请单、退款任务和申请单退款标识。
 *
 * @author ethan
 * @date 2026-08-12
 */
@Component
public class AfterSalesRefundSubmissionManager implements AfterSalesRefundSubmissionGateway {

    private final AfterSalesCaseGateway cases;
    private final RefundCommandGateway refunds;

    public AfterSalesRefundSubmissionManager(
            AfterSalesCaseGateway cases,
            RefundCommandGateway refunds
    ) {
        this.cases = cases;
        this.refunds = refunds;
    }

    @Override
    @Transactional
    public AfterSalesRefundSubmissionResultModel submit(
            AfterSalesCaseModel caseModel,
            RefundCommandModel refundCommand
    ) {
        AfterSalesCaseModel created = cases.create(caseModel);
        if (!created.workflowRunId().equals(caseModel.workflowRunId())) {
            return new AfterSalesRefundSubmissionResultModel(
                    created, refunds.findByCaseId(created.caseId()).orElse(null)
            );
        }
        RefundCommandResultModel command = refunds.create(refundCommand);
        AfterSalesCaseModel updated = created.withRefund(command.refundId(), refundCommand.createdAt());
        if (!cases.update(created, updated)) {
            throw new AfterSalesCaseConflictException("after-sales case version has changed");
        }
        return new AfterSalesRefundSubmissionResultModel(updated, command);
    }
}
