package cn.ethan.core.after_sales.service;

import cn.ethan.core.after_sales.enums.RefundReasonEnum;
import cn.ethan.core.order.service.OrderRequestAnalysisService;

import java.util.Locale;

/**
 * 售后请求分析服务：只提取领域参数，业务流程和写操作路径仍由代码固定。
 *
 * @author ethan
 * @date 2026-08-07
 */
public final class AfterSalesRequestAnalysisService {

    private final OrderRequestAnalysisService orderRequests;

    public AfterSalesRequestAnalysisService(OrderRequestAnalysisService orderRequests) {
        this.orderRequests = orderRequests;
    }

    public boolean looksLikeRefundApply(String message) {
        String normalized = normalize(message);
        return normalized.contains("退款") || normalized.contains("退钱") || normalized.contains("refund");
    }

    public boolean looksLikeRefundStatus(String message) {
        String normalized = normalize(message);
        return (normalized.contains("退款") || normalized.contains("售后") || normalized.contains("refund"))
                && (normalized.contains("状态")
                || normalized.contains("进度")
                || normalized.contains("到账")
                || normalized.contains("查询")
                || normalized.contains("status"));
    }

    public String extractOrderId(String message) {
        return orderRequests.extractOrderId(message);
    }

    public RefundReasonEnum extractReason(String message) {
        String normalized = normalize(message);
        if (normalized.contains("破损") || normalized.contains("损坏")) {
            return RefundReasonEnum.DAMAGED;
        }
        if (normalized.contains("没收到") || normalized.contains("未收到")
                || normalized.contains("没有收到")) {
            return RefundReasonEnum.NOT_RECEIVED;
        }
        if (normalized.contains("质量") || normalized.contains("瑕疵") || normalized.contains("故障")) {
            return RefundReasonEnum.QUALITY_ISSUE;
        }
        if (normalized.contains("其他") || normalized.contains("不想要")) {
            return RefundReasonEnum.OTHER;
        }
        return null;
    }

    private String normalize(String message) {
        return message == null ? "" : message.toLowerCase(Locale.ROOT);
    }
}
