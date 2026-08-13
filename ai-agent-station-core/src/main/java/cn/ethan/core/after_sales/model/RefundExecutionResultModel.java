package cn.ethan.core.after_sales.model;

/**
 * 退款渠道执行结果模型：仅表达外部执行是否成功与可公开的稳定失败码。
 *
 * @author ethan
 * @date 2026-08-12
 */
public record RefundExecutionResultModel(boolean completed, String failureCode) {

    public RefundExecutionResultModel {
        failureCode = failureCode == null ? "" : failureCode.strip();
        if (!completed && failureCode.isBlank()) {
            throw new IllegalArgumentException("refund failure code is required");
        }
    }

    public static RefundExecutionResultModel succeeded() {
        return new RefundExecutionResultModel(true, "");
    }

    public static RefundExecutionResultModel failed(String failureCode) {
        return new RefundExecutionResultModel(false, failureCode);
    }
}
