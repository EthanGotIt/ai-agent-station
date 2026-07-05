package cn.ethan.ai.infrastructure.adapter.mcp;

import cn.ethan.ai.domain.agent.model.AfterSalesCaseView;
import cn.ethan.ai.domain.agent.model.AfterSalesOrderSnapshot;
import cn.ethan.ai.domain.agent.port.driven.IAfterSalesRepository;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * 售后退款业务的 MCP Server。
 *
 * <p>通过 {@link McpTool} 将领域能力暴露为标准的 Model Context Protocol 工具，
 * 使任意 MCP Client（包括其他 Agent、IDE、Claude Desktop 等）都能调用。
 */
@Component
public class AfterSalesMcpServer {

    private final IAfterSalesRepository afterSalesRepository;

    public AfterSalesMcpServer(IAfterSalesRepository afterSalesRepository) {
        this.afterSalesRepository = afterSalesRepository;
    }

    @McpTool(name = "query_order",
            description = "按订单号查询订单状态，只读工具，用于售后退款前确认订单信息")
    public Map<String, Object> queryOrder(
            @McpToolParam(description = "订单号", required = true) String orderId,
            @McpToolParam(description = "请求用户ID", required = true) String requesterId) {
        Optional<AfterSalesOrderSnapshot> order = afterSalesRepository.findOrder(orderId, requesterId);
        if (order.isEmpty()) {
            return Map.of("success", false, "errorType", "ORDER_NOT_FOUND", "message", "订单不存在");
        }
        AfterSalesOrderSnapshot snapshot = order.get();
        return Map.of(
                "success", true,
                "orderId", snapshot.orderId(),
                "ownerId", snapshot.ownerId(),
                "status", snapshot.status(),
                "daysSinceDelivery", snapshot.daysSinceDelivery()
        );
    }

    @McpTool(name = "query_after_sales_case",
            description = "按 caseId 查询售后退款 Case 当前状态")
    public Map<String, Object> queryCase(
            @McpToolParam(description = "售后 Case ID", required = true) String caseId) {
        Optional<AfterSalesCaseView> caseView = afterSalesRepository.findCase(caseId);
        if (caseView.isEmpty()) {
            return Map.of("success", false, "message", "Case 不存在");
        }
        AfterSalesCaseView view = caseView.get();
        assert view.caseIdValue() != null;
        assert view.orderIdValue() != null;
        return Map.of(
                "success", true,
                "caseId", view.caseIdValue(),
                "stage", view.stage(),
                "orderId", view.orderIdValue(),
                "checkpointId", view.checkpointId(),
                "terminalReason", view.terminalReason()
        );
    }
}
