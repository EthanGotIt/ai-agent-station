package cn.ethan.core.agent.workflow;

import cn.ethan.core.commerce.order.OrderSnapshotModel;
import cn.ethan.core.commerce.order.OrderStatusEnum;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 类型职责：核验退款与删除授权只绑定业务相关字段和既有资格。
 *
 * @author ethan
 * @date 2026-09-28
 */
class OrderWriteJavaWorkflowPolicyTest {

    private static final Instant NOW = Instant.parse("2026-09-28T00:00:00Z");

    @Test
    void refundBindsReasonAmountCurrencyAndQualificationWithoutLogisticsNoise() {
        RefundJavaWorkflowPolicy policy = new RefundJavaWorkflowPolicy();
        OrderSnapshotModel paid = order(OrderStatusEnum.PAID, "待发货", new BigDecimal("99.00"), null);
        assertTrue(policy.eligible(paid, "user-1"));
        assertTrue(policy.eligible(order(OrderStatusEnum.REFUNDED, "待发货", new BigDecimal("99.00"), null),
                "user-1"));
        assertFalse(policy.eligible(order(OrderStatusEnum.CANCELLED, "待发货", new BigDecimal("99.00"), null),
                "user-1"));
        assertEquals(policy.factsFingerprint(paid, "不需要"),
                policy.factsFingerprint(order(OrderStatusEnum.PAID, "物流更新", new BigDecimal("99.00"), null),
                        "不需要"));
        assertNotEquals(policy.factsFingerprint(paid, "不需要"), policy.factsFingerprint(paid, "收到破损"));
        assertNotEquals(policy.factsFingerprint(paid, "不需要"), policy.factsFingerprint(
                order(OrderStatusEnum.PAID, "待发货", new BigDecimal("98.00"), null), "不需要"));
    }

    @Test
    void deletionBindsIrreversibleScopeAndCurrentEligibility() {
        DeleteJavaWorkflowPolicy policy = new DeleteJavaWorkflowPolicy();
        OrderSnapshotModel paid = order(OrderStatusEnum.PAID, "待发货", new BigDecimal("99.00"), null);
        assertTrue(policy.eligible(paid, "user-1"));
        assertTrue(policy.eligible(order(OrderStatusEnum.CANCELLED, "待发货", new BigDecimal("99.00"), null),
                "user-1"));
        assertFalse(policy.eligible(paid, "other-user"));
        assertEquals("ORDER_AND_LOGISTICS", policy.commandPayload(paid, "").get("scope"));
        assertEquals(policy.factsFingerprint(paid, ""), policy.factsFingerprint(
                order(OrderStatusEnum.PAID, "物流更新", new BigDecimal("100.00"), null), ""));
        assertNotEquals(policy.factsFingerprint(paid, ""), policy.factsFingerprint(
                order(OrderStatusEnum.PAID, "待发货", new BigDecimal("99.00"), NOW), ""));
    }

    private OrderSnapshotModel order(OrderStatusEnum status, String logistics, BigDecimal amount, Instant hiddenAt) {
        return new OrderSnapshotModel("ORDER-1", "user-1", status, 0, NOW, NOW, NOW,
                logistics, amount, "CNY", "商品", hiddenAt);
    }
}
