package cn.ethan.dto;

import cn.ethan.core.after_sales.enums.AfterSalesReviewDecisionEnum;
import cn.ethan.core.after_sales.model.AfterSalesReviewRequestModel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 售后审核决策请求 DTO：在 HTTP 边界校验幂等标识、版本和决策文本。
 *
 * @author ethan
 * @date 2026-08-12
 */
public record AgentAfterSalesReviewDecisionRequestDto(
        @NotBlank @Size(max = 128) String decisionId,
        @PositiveOrZero long expectedVersion,
        @NotNull AfterSalesReviewDecisionEnum decision,
        @Size(max = 500) String note
) {

    public AfterSalesReviewRequestModel toModel(String caseId) {
        return new AfterSalesReviewRequestModel(caseId, decisionId, expectedVersion, decision, note);
    }
}
