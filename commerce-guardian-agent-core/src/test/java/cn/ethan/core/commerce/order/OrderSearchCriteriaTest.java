package cn.ethan.core.commerce.order;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 订单搜索条件测试：保证模型调用产生的筛选值不会越过 Core 边界。
 *
 * @author ethan
 * @date 2026-08-22
 */
class OrderSearchCriteriaTest {

    @Test
    void normalizesKeywordAndDefaultsVisibility() {
        OrderSearchCriteria criteria = new OrderSearchCriteria(
                null, null, null, null, Set.of(OrderStatusEnum.PAID), "  耳机  ", null, null);

        assertEquals("耳机", criteria.keyword());
        assertEquals(OrderVisibilityEnum.ACTIVE, criteria.visibility());
        assertEquals(20, criteria.limit());
    }

    @Test
    void rejectsInvalidTimeAmountStalledDaysAndLimit() {
        assertThrows(IllegalArgumentException.class, () -> new OrderSearchCriteria(
                Instant.parse("2026-08-23T00:00:00Z"), Instant.parse("2026-08-22T00:00:00Z"),
                null, null, Set.of(), null, null, OrderVisibilityEnum.ACTIVE));
        assertThrows(IllegalArgumentException.class, () -> new OrderSearchCriteria(
                null, null, new BigDecimal("100"), new BigDecimal("10"), Set.of(), null,
                null, OrderVisibilityEnum.ACTIVE));
        assertThrows(IllegalArgumentException.class, () -> new OrderSearchCriteria(
                null, null, null, null, Set.of(), null, 0, OrderVisibilityEnum.ACTIVE));
        assertThrows(IllegalArgumentException.class, () -> new OrderSearchCriteria(
                null, null, null, null, Set.of(), null, null, OrderVisibilityEnum.ACTIVE, 51));
    }
}
