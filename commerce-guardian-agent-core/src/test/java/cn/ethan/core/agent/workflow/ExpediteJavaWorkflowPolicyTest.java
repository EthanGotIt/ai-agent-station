package cn.ethan.core.agent.workflow;

import cn.ethan.core.commerce.order.OrderSnapshotModel;
import cn.ethan.core.commerce.order.OrderStatusEnum;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 类型职责：验证 Java 催发货授权指纹只绑定归属、对象、确认参数和资格状态。
 *
 * @author ethan
 * @date 2026-09-24
 */
class ExpediteJavaWorkflowPolicyTest {

    private final ExpediteJavaWorkflowPolicy policy = new ExpediteJavaWorkflowPolicy();

    @Test
    void ignoresDisplayAndLogisticsChangesButBindsEligibilityAndConfirmation() {
        OrderSnapshotModel original = order("user-1", OrderStatusEnum.PAID, "商品摘要 A");
        OrderSnapshotModel displayChanged = new OrderSnapshotModel("ORDER-1", "user-1", OrderStatusEnum.PAID,
                91, Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-09-30T00:00:00Z"),
                Instant.parse("2026-09-20T00:00:00Z"), "物流展示变化", null, null, "商品摘要 B", null);

        String fingerprint = policy.factsFingerprint(original, "请尽快发货");

        assertEquals(fingerprint, policy.factsFingerprint(displayChanged, "请尽快发货"));
        assertNotEquals(fingerprint, policy.factsFingerprint(
                order("user-2", OrderStatusEnum.PAID, "商品摘要 A"), "请尽快发货"));
        assertNotEquals(fingerprint, policy.factsFingerprint(
                order("user-1", OrderStatusEnum.SHIPPED, "商品摘要 A"), "请尽快发货"));
        assertNotEquals(fingerprint, policy.factsFingerprint(original, "另一个催发货原因"));
        assertTrue(policy.eligible(original, "user-1"));
        assertFalse(policy.eligible(original, "user-2"));
    }

    private OrderSnapshotModel order(String userId, OrderStatusEnum status, String summary) {
        return new OrderSnapshotModel("ORDER-1", userId, status, 2,
                Instant.parse("2026-09-10T00:00:00Z"), Instant.parse("2026-09-15T00:00:00Z"),
                Instant.parse("2026-09-12T00:00:00Z"), "PAID", null, null, summary, null);
    }
}
