package cn.ethan.dto;

import cn.ethan.core.after_sales.model.AfterSalesRefundRetryRequestModel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 售后退款重试请求 DTO：为失败退款提供受版本和幂等键保护的人工重试入口。
 *
 * @author ethan
 * @date 2026-08-12
 */
public record AgentAfterSalesRefundRetryRequestDto(
        @NotBlank @Size(max = 128) String retryId,
        @PositiveOrZero long expectedVersion
) {

    public AfterSalesRefundRetryRequestModel toModel(String caseId) {
        return new AfterSalesRefundRetryRequestModel(caseId, retryId, expectedVersion);
    }
}
