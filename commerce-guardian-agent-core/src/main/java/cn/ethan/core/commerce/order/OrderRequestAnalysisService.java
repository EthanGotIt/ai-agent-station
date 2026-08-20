package cn.ethan.core.commerce.order;


import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 订单请求分析服务：集中提取订单号并区分查询、诊断和开放式订单问题。
 *
 * @author ethan
 * @date 2026-08-06
 */
public final class OrderRequestAnalysisService {

    private static final Pattern ORDER_TOKEN = Pattern.compile(
            "(?i)\\bORDER[-_][A-Z0-9]+(?:[-_][A-Z0-9]+)*\\b"
    );
    private static final Pattern ORDER_WORD = Pattern.compile("(?i)\\border\\b");
    private static final Pattern CHINESE_TOKEN = Pattern.compile(
            "(?i)订单(?:号|编号)?[：:\\s]*([A-Z0-9_-]*\\d[A-Z0-9_-]*)"
    );

    public String extractOrderId(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        Matcher orderMatcher = ORDER_TOKEN.matcher(message);
        if (orderMatcher.find()) {
            return orderMatcher.group().toUpperCase(Locale.ROOT);
        }

        Matcher chineseMatcher = CHINESE_TOKEN.matcher(message);
        if (chineseMatcher.find()) {
            return chineseMatcher.group(1).toUpperCase(Locale.ROOT);
        }
        return null;
    }

    public boolean looksLikeOrderQuery(String message) {
        String normalized = normalize(message);
        boolean orderSubject = hasOrderSubject(normalized);
        boolean queryIntent = normalized.contains("查询")
                || normalized.contains("查看")
                || normalized.contains("状态")
                || normalized.contains("进度")
                || normalized.contains("到哪")
                || normalized.contains("我的订单")
                || normalized.contains("track")
                || normalized.contains("status")
                || normalized.contains("where is");
        return orderSubject && queryIntent;
    }

    public boolean looksLikeOrderTracking(String message) {
        String normalized = normalize(message);
        return hasOrderSubject(normalized) && (normalized.contains("物流")
                || normalized.contains("配送")
                || normalized.contains("轨迹")
                || normalized.contains("到哪")
                || normalized.contains("运输"));
    }

    public boolean looksLikeOrderDiagnosis(String message) {
        String normalized = normalize(message);
        boolean diagnosisIntent = normalized.contains("异常")
                || normalized.contains("延迟")
                || normalized.contains("没发货")
                || normalized.contains("未发货")
                || normalized.contains("没收到")
                || normalized.contains("未收到")
                || normalized.contains("卡住")
                || normalized.contains("停滞")
                || normalized.contains("超时")
                || normalized.contains("逾期")
                || normalized.contains("怎么还")
                || normalized.contains("为什么")
                || normalized.contains("怎么办");
        return hasOrderSubject(normalized) && diagnosisIntent;
    }

    /**
     * 判断是否需要跳过确定性订单流程，交由 ReAct 执行跨工具的只读分析。
     *
     * @param message 用户消息
     * @return 包含订单主题和多维分析意图时返回 true
     */
    public boolean requiresReActOrderResearch(String message) {
        String normalized = normalize(message);
        return hasOrderSubject(normalized) && (normalized.contains("对比")
                || normalized.contains("比较")
                || normalized.contains("综合分析")
                || normalized.contains("复盘")
                || normalized.contains("趋势")
                || normalized.contains("总结"));
    }

    /**
     * 本期订单领域仅支持只读查询与诊断；涉及写入的售后意图必须等待专用领域工作流。
     *
     * @param message 用户消息
     * @return 是否为当前未实现的写操作
     */
    public boolean requiresUnsupportedOrderWrite(String message) {
        String normalized = normalize(message);
        boolean addressChange = normalized.contains("修改地址")
                || normalized.contains("改地址")
                || normalized.contains("改收货地址")
                || normalized.contains("换地址")
                || (normalized.contains("修改") && normalized.contains("地址"));
        return hasOrderSubject(normalized)
                && (normalized.contains("退货")
                || normalized.contains("取消订单")
                || normalized.contains("取消购买")
                || normalized.contains("取消交易")
                || addressChange
                || normalized.contains("重新发货")
                || normalized.contains("补发"));
    }

    public boolean hasDeliveryDispute(String message) {
        String normalized = normalize(message);
        return normalized.contains("没收到")
                || normalized.contains("未收到")
                || normalized.contains("没有收到");
    }

    public OrderIssueTypeEnum extractIssueType(String message) {
        String normalized = normalize(message);
        if (normalized.contains("没发货") || normalized.contains("未发货")) {
            return OrderIssueTypeEnum.NOT_SHIPPED;
        }
        if (normalized.contains("卡住") || normalized.contains("停滞") || normalized.contains("没更新")) {
            return OrderIssueTypeEnum.LOGISTICS_STALLED;
        }
        if (normalized.contains("逾期") || normalized.contains("超时") || normalized.contains("晚到")) {
            return OrderIssueTypeEnum.DELIVERY_OVERDUE;
        }
        if (hasDeliveryDispute(normalized)) {
            return OrderIssueTypeEnum.DELIVERED_NOT_RECEIVED;
        }
        return null;
    }

    private boolean hasOrderSubject(String normalized) {
        return extractOrderId(normalized) != null
                || normalized.contains("订单")
                || ORDER_WORD.matcher(normalized).find()
                || normalized.contains("物流")
                || normalized.contains("配送")
                || normalized.contains("购买")
                || normalized.contains("交易")
                || normalized.contains("收货地址");
    }

    private String normalize(String message) {
        return message == null ? "" : message.toLowerCase(Locale.ROOT);
    }
}
