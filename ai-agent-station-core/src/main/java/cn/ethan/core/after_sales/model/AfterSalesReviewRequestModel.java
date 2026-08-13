package cn.ethan.core.after_sales.model;

import cn.ethan.core.after_sales.enums.AfterSalesReviewDecisionEnum;

/**
 * 售后审核请求模型：携带幂等决策标识和乐观锁版本，防止重复或陈旧审核。
 *
 * @author ethan
 * @date 2026-08-12
 */
public record AfterSalesReviewRequestModel(
        String caseId,
        String decisionId,
        long expectedVersion,
        AfterSalesReviewDecisionEnum decision,
        String note
) {

    public AfterSalesReviewRequestModel {
        caseId = required(caseId, "caseId");
        decisionId = required(decisionId, "decisionId");
        if (expectedVersion < 0 || decision == null) {
            throw new IllegalArgumentException("after-sales review request is invalid");
        }
        note = note == null ? "" : note.strip();
        if (decision == AfterSalesReviewDecisionEnum.REJECT && note.isBlank()) {
            throw new IllegalArgumentException("reject review note is required");
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank() || value.strip().length() > 128) {
            throw new IllegalArgumentException(name + " is required and must not exceed 128 characters");
        }
        return value.strip();
    }
}
