package cn.ethan.core.after_sales.port;

import cn.ethan.core.after_sales.model.AfterSalesCaseModel;
import cn.ethan.core.after_sales.model.AfterSalesRefundSubmissionResultModel;
import cn.ethan.core.after_sales.model.RefundCommandModel;

/**
 * 自动退款提交网关：原子创建申请单、退款命令并回写退款标识。
 *
 * @author ethan
 * @date 2026-08-12
 */
public interface AfterSalesRefundSubmissionGateway {

    AfterSalesRefundSubmissionResultModel submit(
            AfterSalesCaseModel caseModel,
            RefundCommandModel refundCommand
    );
}
