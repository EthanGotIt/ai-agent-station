package cn.ethan.core.after_sales.model;

/**
 * 退款人工重试请求模型：使用独立重试标识保护失败任务的重复提交。
 *
 * @author ethan
 * @date 2026-08-12
 */
public record AfterSalesRefundRetryRequestModel(
        String caseId,
        String retryId,
        long expectedVersion
) {

    public AfterSalesRefundRetryRequestModel {
        caseId = required(caseId, "caseId");
        retryId = required(retryId, "retryId");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank() || value.strip().length() > 128) {
            throw new IllegalArgumentException(name + " is required and must not exceed 128 characters");
        }
        return value.strip();
    }
}
