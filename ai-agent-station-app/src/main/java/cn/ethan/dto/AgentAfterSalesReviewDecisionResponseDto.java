package cn.ethan.dto;

import cn.ethan.core.after_sales.model.AfterSalesReviewResultModel;

/**
 * 售后审核决策响应 DTO：返回决策后最新申请单，便于控制台无额外猜测地刷新。
 *
 * @author ethan
 * @date 2026-08-12
 */
public record AgentAfterSalesReviewDecisionResponseDto(AgentAfterSalesCaseDto caseModel) {

    public static AgentAfterSalesReviewDecisionResponseDto from(AfterSalesReviewResultModel result) {
        return new AgentAfterSalesReviewDecisionResponseDto(
                AgentAfterSalesCaseDto.from(result.caseModel(), result.refundCommand())
        );
    }
}
