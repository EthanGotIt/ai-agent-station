package cn.ethan.core.commerce.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 订单服务的结构化搜索条件：只表达筛选事实，不承担自然语言解析。
 *
 * @author ethan
 * @date 2026-08-22
 */
public record OrderSearchCriteria(
        Instant createdFrom,
        Instant createdTo,
        BigDecimal minAmount,
        BigDecimal maxAmount,
        Set<OrderStatusEnum> statuses,
        String keyword,
        Integer logisticsStalledDays,
        OrderVisibilityEnum visibility,
        int limit
) {

    public OrderSearchCriteria {
        if (createdFrom != null && createdTo != null && createdFrom.isAfter(createdTo)) {
            throw new IllegalArgumentException("订单创建时间范围无效");
        }
        if (minAmount != null && minAmount.signum() < 0
                || maxAmount != null && maxAmount.signum() < 0) {
            throw new IllegalArgumentException("订单金额不能为负数");
        }
        if (minAmount != null && maxAmount != null && minAmount.compareTo(maxAmount) > 0) {
            throw new IllegalArgumentException("订单金额范围无效");
        }
        if (logisticsStalledDays != null && (logisticsStalledDays < 1 || logisticsStalledDays > 365)) {
            throw new IllegalArgumentException("物流停滞天数必须在 1 到 365 之间");
        }
        statuses = statuses == null ? Set.of() : Set.copyOf(new LinkedHashSet<>(statuses));
        keyword = keyword == null || keyword.isBlank() ? null : keyword.strip();
        visibility = visibility == null ? OrderVisibilityEnum.ACTIVE : visibility;
        if (limit < 1 || limit > 50) {
            throw new IllegalArgumentException("订单搜索结果数量必须在 1 到 50 之间");
        }
    }

    public OrderSearchCriteria(
            Instant createdFrom,
            Instant createdTo,
            BigDecimal minAmount,
            BigDecimal maxAmount,
            Set<OrderStatusEnum> statuses,
            String keyword,
            Integer logisticsStalledDays,
            OrderVisibilityEnum visibility
    ) {
        this(createdFrom, createdTo, minAmount, maxAmount, statuses, keyword,
                logisticsStalledDays, visibility, 20);
    }

    public static OrderSearchCriteria latest(int limit) {
        return new OrderSearchCriteria(null, null, null, null, Set.of(), null,
                null, OrderVisibilityEnum.ACTIVE, limit);
    }

    public List<OrderStatusEnum> statusList() {
        return List.copyOf(statuses);
    }
}
