package cn.ethan.core.after_sales.model;

/**
 * 自动退款提交结果模型：保证申请单和退款任务以同一业务结果返回给 Workflow。
 *
 * @author ethan
 * @date 2026-08-12
 */
public record AfterSalesRefundSubmissionResultModel(
        AfterSalesCaseModel caseModel,
        RefundCommandResultModel refundCommand
) {

    public AfterSalesRefundSubmissionResultModel {
        if (caseModel == null) {
            throw new IllegalArgumentException("after-sales refund submission case is required");
        }
    }
}
