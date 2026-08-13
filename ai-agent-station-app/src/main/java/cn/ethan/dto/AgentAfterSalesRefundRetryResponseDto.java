package cn.ethan.dto;

import cn.ethan.core.after_sales.model.AfterSalesReviewResultModel;

/**
 * 退款人工重试响应 DTO：返回重新排队后最新申请单和退款任务状态。
 *
 * @author ethan
 * @date 2026-08-12
 */
public record AgentAfterSalesRefundRetryResponseDto(AgentAfterSalesCaseDto caseModel) {

    public static AgentAfterSalesRefundRetryResponseDto from(AfterSalesReviewResultModel result) {
        return new AgentAfterSalesRefundRetryResponseDto(
                AgentAfterSalesCaseDto.from(result.caseModel(), result.refundCommand())
        );
    }
}
