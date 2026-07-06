package cn.ethan.ai.domain.agent.policy;

import java.util.Locale;
import java.util.Set;

import static cn.ethan.ai.types.common.util.Strings.isBlank;

public final class AfterSalesRefundEligibilityPolicy {

    private static final Set<String> DELIVERED_REFUND_REASONS = Set.of(
            "DAMAGED", "WRONG_ITEM", "QUALITY_ISSUE"
    );

    public RefundDecision evaluate(RefundRequest request) {
        if (isBlank(request.userId()) || isBlank(request.orderId())) {
            return new RefundDecision(RefundOutcome.NEED_USER_INPUT, "MISSING_REQUIRED_IDENTITY");
        }
        if (isBlank(request.orderOwnerId())) {
            return new RefundDecision(RefundOutcome.REJECTED, "ORDER_OWNER_UNKNOWN");
        }
        if (!request.userId().equals(request.orderOwnerId())) {
            return new RefundDecision(RefundOutcome.REJECTED, "ORDER_NOT_OWNED");
        }

        String status = normalize(request.orderStatus());
        switch (status) {
            case "REFUNDED" -> {
                return new RefundDecision(RefundOutcome.ALREADY_COMPLETED, "ALREADY_REFUNDED");
            }
            case "PAID", "PROCESSING" -> {
                return new RefundDecision(RefundOutcome.ELIGIBLE, "REFUND_REQUIRES_APPROVAL");
            }
            case "DELIVERED" -> {
                String reason = normalize(request.refundReason());
                boolean withinWindow = request.daysSinceDelivery() != null
                        && request.daysSinceDelivery() >= 0
                        && request.daysSinceDelivery() <= 7;
                if (withinWindow && DELIVERED_REFUND_REASONS.contains(reason)) {
                    return new RefundDecision(RefundOutcome.ELIGIBLE, "REFUND_REQUIRES_APPROVAL");
                }
                return new RefundDecision(RefundOutcome.REJECTED, "DELIVERED_REFUND_RULE_NOT_MET");
            }
        }
        return new RefundDecision(RefundOutcome.REJECTED, "ORDER_STATUS_NOT_REFUNDABLE");
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    public enum RefundOutcome {
        NEED_USER_INPUT,
        ELIGIBLE,
        ALREADY_COMPLETED,
        REJECTED
    }

    public record RefundRequest(String userId,
                                String orderId,
                                String orderOwnerId,
                                String orderStatus,
                                String refundReason,
                                Integer daysSinceDelivery) {
    }

    public record RefundDecision(RefundOutcome outcome, String reason) {
    }
}
