package cn.ethan.infrastructure.agent.coordination.springai;

import cn.ethan.core.commerce.order.LogisticsEventModel;
import cn.ethan.core.commerce.order.OrderLookupResultModel;
import cn.ethan.core.commerce.order.OrderSearchCriteria;
import cn.ethan.core.commerce.order.OrderSearchResultModel;
import cn.ethan.core.commerce.order.OrderSnapshotModel;
import cn.ethan.core.commerce.order.OrderStatusEnum;
import cn.ethan.core.commerce.order.OrderVisibilityEnum;
import cn.ethan.core.commerce.order.OrderSearchStatusEnum;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * 类型职责：集中订单只读 Tool 的参数清洗、事实 JSON 和受控输出格式化。
 *
 * @author ethan
 * @date 2026-08-24
 */
public final class SpringAiOrderToolSupport {

    private SpringAiOrderToolSupport() {
    }

    public static String requiredArgument(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Tool 参数不能为空：" + name);
        }
        return value.trim();
    }

    public static String renderOrderLookup(OrderLookupResultModel lookup) {
        if (lookup == null) {
            return "{\"status\":\"TEMPORARY_FAILURE\"}";
        }
        StringBuilder value = new StringBuilder("{\"status\":\"")
                .append(escapeJson(lookup.status().name())).append('"');
        if ("FOUND".equals(lookup.status().name()) && lookup.order() != null) {
            OrderSnapshotModel order = lookup.order();
            appendJsonString(value, "orderId", order.orderId());
            appendJsonString(value, "orderStatus", order.status().name());
            appendJsonNumber(value, "daysSinceDelivery", order.daysSinceDelivery());
            appendJsonString(value, "createdAt", instant(order.createdAt()));
            appendJsonString(value, "expectedDeliveryAt", instant(order.expectedDeliveryAt()));
            appendJsonString(value, "lastLogisticsAt", instant(order.lastLogisticsAt()));
            appendJsonString(value, "logisticsStatus", order.logisticsStatus());
            appendJsonNumber(value, "paidAmount", order.paidAmount());
            appendJsonString(value, "currency", order.currency());
            appendJsonString(value, "itemSummary", order.itemSummary());
            appendJsonString(value, "visibility", order.hiddenAt() == null ? "ACTIVE" : "HIDDEN");
        }
        return value.append('}').toString();
    }

    public static String renderOrderSearch(OrderSearchResultModel result) {
        StringBuilder value = new StringBuilder("{\"status\":\"")
                .append(escapeJson(result == null || result.status() == null
                        ? OrderSearchStatusEnum.TEMPORARY_FAILURE.name() : result.status().name()))
                .append("\",\"orders\":[");
        boolean first = true;
        if (result != null && result.orders() != null) {
            for (OrderSnapshotModel order : result.orders()) {
                if (order == null) continue;
                if (!first) value.append(',');
                value.append(renderOrderSnapshot(order));
                first = false;
            }
        }
        return value.append("]}").toString();
    }

    public static String renderOrderSnapshot(OrderSnapshotModel order) {
        StringBuilder value = new StringBuilder();
        appendJsonString(value, "orderId", order.orderId());
        appendJsonString(value, "orderStatus", order.status().name());
        appendJsonNumber(value, "daysSinceDelivery", order.daysSinceDelivery());
        appendJsonString(value, "createdAt", instant(order.createdAt()));
        appendJsonString(value, "expectedDeliveryAt", instant(order.expectedDeliveryAt()));
        appendJsonString(value, "lastLogisticsAt", instant(order.lastLogisticsAt()));
        appendJsonString(value, "logisticsStatus", order.logisticsStatus());
        appendJsonNumber(value, "paidAmount", order.paidAmount());
        appendJsonString(value, "currency", order.currency());
        appendJsonString(value, "itemSummary", order.itemSummary());
        appendJsonString(value, "visibility", order.hiddenAt() == null ? "ACTIVE" : "HIDDEN");
        return "{" + value.substring(1) + "}";
    }

    public static String renderLogistics(String orderId, List<LogisticsEventModel> trace) {
        StringBuilder value = new StringBuilder("{\"orderId\":\"")
                .append(escapeJson(orderId)).append("\",\"events\":[");
        boolean first = true;
        if (trace != null) {
            for (LogisticsEventModel event : trace) {
                if (event == null) {
                    continue;
                }
                if (!first) {
                    value.append(',');
                }
                StringBuilder eventJson = new StringBuilder();
                appendJsonString(eventJson, "eventId", event.eventId());
                appendJsonString(eventJson, "orderId", event.orderId());
                appendJsonString(eventJson, "status", event.status());
                appendJsonString(eventJson, "location", event.location());
                appendJsonString(eventJson, "description", event.description());
                appendJsonString(eventJson, "occurredAt", instant(event.occurredAt()));
                value.append('{').append(eventJson.substring(1)).append('}');
                first = false;
            }
        }
        return value.append("]}").toString();
    }

    public static String renderLogistics(List<LogisticsEventModel> trace) {
        return renderLogistics("", trace);
    }

    public static String instant(Instant value) {
        return value == null ? null : value.toString();
    }

    public static void appendJsonString(StringBuilder target, String name, String value) {
        if (value == null) {
            return;
        }
        target.append(",\"").append(escapeJson(name)).append("\":\"")
                .append(escapeJson(value)).append('"');
    }

    public static void appendJsonNumber(StringBuilder target, String name, Number value) {
        if (value == null) {
            return;
        }
        target.append(",\"").append(escapeJson(name)).append("\":").append(value);
    }

    public static String boundToolValue(String value) {
        if (value == null || value.length() <= 2_000) {
            return value == null ? "" : value;
        }
        return value.substring(0, 1_980) + "…[TOOL_RESULT_TRUNCATED]";
    }

    public static OrderSearchCriteria parseSearchCriteria(
            String createdFrom,
            String createdTo,
            String minAmount,
            String maxAmount,
            String statuses,
            String keyword,
            String logisticsStalledDays
    ) {
        Set<OrderStatusEnum> parsedStatuses = statuses == null || statuses.isBlank()
                ? Set.of()
                : Arrays.stream(statuses.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(value -> OrderStatusEnum.valueOf(value.toUpperCase()))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new OrderSearchCriteria(
                parseBoundary("createdFrom", createdFrom, false),
                parseBoundary("createdTo", createdTo, true),
                parseAmount("minAmount", minAmount),
                parseAmount("maxAmount", maxAmount),
                parsedStatuses, keyword, parseStalledDays(logisticsStalledDays), OrderVisibilityEnum.ACTIVE);
    }

    public static BigDecimal parseAmount(String name, String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Tool 参数不是有效金额：" + name);
        }
    }

    public static Integer parseStalledDays(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Tool 参数不是有效物流停滞天数：logisticsStalledDays");
        }
    }

    public static Instant parseBoundary(String name, String value, boolean endOfDay) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        try {
            return Instant.parse(normalized);
        } catch (DateTimeParseException instantParseFailure) {
            try {
                LocalDate date = LocalDate.parse(normalized);
                return date.atTime(endOfDay ? LocalTime.MAX : LocalTime.MIN)
                        .toInstant(ZoneOffset.UTC);
            } catch (DateTimeParseException failure) {
                throw new IllegalArgumentException("Tool 参数不是有效日期：" + name);
            }
        }
    }

    public static String escapeJson(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }
}
