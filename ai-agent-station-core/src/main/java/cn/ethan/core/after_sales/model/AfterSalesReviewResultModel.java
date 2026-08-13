package cn.ethan.core.after_sales.model;

/**
 * 售后审核结果模型：将最新申请单与可选退款命令一并返回给应用边界。
 *
 * @author ethan
 * @date 2026-08-12
 */
public record AfterSalesReviewResultModel(
        AfterSalesCaseModel caseModel,
        RefundCommandResultModel refundCommand
) {

    public AfterSalesReviewResultModel {
        if (caseModel == null) {
            throw new IllegalArgumentException("after-sales review case is required");
        }
    }
}
