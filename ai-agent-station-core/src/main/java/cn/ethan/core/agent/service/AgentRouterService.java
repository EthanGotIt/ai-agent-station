package cn.ethan.core.agent.service;

import cn.ethan.core.agent.enums.RouteTypeEnum;
import cn.ethan.core.agent.model.AgentRequestModel;
import cn.ethan.core.agent.model.ConversationMessageModel;
import cn.ethan.core.agent.model.RouteDecisionModel;
import cn.ethan.core.agent.port.RouteDecisionProvider;
import cn.ethan.core.agent.support.CancellationToken;
import cn.ethan.core.after_sales.enums.AfterSalesOperationEnum;
import cn.ethan.core.after_sales.enums.RefundReasonEnum;
import cn.ethan.core.after_sales.service.AfterSalesRequestAnalysisService;
import cn.ethan.core.order.enums.OrderInquiryOperationEnum;
import cn.ethan.core.order.service.OrderRequestAnalysisService;
import cn.ethan.core.workflow.order.OrderInquiryWorkflow;
import cn.ethan.core.workflow.after_sales.AfterSalesRefundWorkflow;
import cn.ethan.core.workflow.service.WorkflowRegistryService;

import java.util.List;
import java.util.Map;

/**
 * Agent 路由服务：按规则优先、模型兜底的顺序生成受控路由决策。
 *
 * @author ethan
 * @date 2026-08-05
 */
public final class AgentRouterService {

    public static final String CLOCK_EXECUTOR_ID = "clock";
    public static final String REACT_EXECUTOR_ID = "react";

    private final RouteDecisionProvider decisionProvider;
    private final WorkflowRegistryService workflows;
    private final OrderRequestAnalysisService orderRequestAnalysis;
    private final AfterSalesRequestAnalysisService afterSalesRequestAnalysis;
    private final SessionPreferenceRequestAnalysisService sessionPreferenceRequestAnalysis;

    public AgentRouterService(
            RouteDecisionProvider decisionProvider,
            WorkflowRegistryService workflows,
            OrderRequestAnalysisService orderRequestAnalysis
    ) {
        this(
                decisionProvider,
                workflows,
                orderRequestAnalysis,
                new AfterSalesRequestAnalysisService(orderRequestAnalysis)
        );
    }

    public AgentRouterService(
            RouteDecisionProvider decisionProvider,
            WorkflowRegistryService workflows,
            OrderRequestAnalysisService orderRequestAnalysis,
            AfterSalesRequestAnalysisService afterSalesRequestAnalysis
    ) {
        this.decisionProvider = decisionProvider;
        this.workflows = workflows;
        this.orderRequestAnalysis = orderRequestAnalysis;
        this.afterSalesRequestAnalysis = afterSalesRequestAnalysis;
        this.sessionPreferenceRequestAnalysis = new SessionPreferenceRequestAnalysisService();
    }

    public RouteDecisionModel route(AgentRequestModel request, String userId,
                                    CancellationToken cancellationToken) {
        return route(request, userId, List.of(), cancellationToken);
    }

    public RouteDecisionModel route(
            AgentRequestModel request,
            String userId,
            List<ConversationMessageModel> history,
            CancellationToken cancellationToken
    ) {
        cancellationToken.throwIfCancelled();
        String message = request.normalizedMessage();

        if (isClockRequest(message)) {
            return new RouteDecisionModel(
                    RouteTypeEnum.ATOMIC,
                    CLOCK_EXECUTOR_ID,
                    "query-current-time",
                    List.of(),
                    "RULE_CLOCK"
            );
        }
        if (sessionPreferenceRequestAnalysis.requiresSessionPreferenceSave(message)) {
            return new RouteDecisionModel(
                    RouteTypeEnum.REACT,
                    REACT_EXECUTOR_ID,
                    "save-session-preference",
                    List.of(),
                    "RULE_SESSION_PREFERENCE_SAVE"
            );
        }
        if (afterSalesRequestAnalysis.looksLikeAfterSalesPolicyAnalysis(message)) {
            return new RouteDecisionModel(
                    RouteTypeEnum.REACT,
                    REACT_EXECUTOR_ID,
                    "after-sales-policy-analysis",
                    List.of(),
                    "RULE_AFTER_SALES_POLICY_ANALYSIS"
            );
        }
        if (afterSalesRequestAnalysis.looksLikeRefundStatus(message)) {
            return afterSalesDecision(
                    AfterSalesOperationEnum.QUERY_STATUS,
                    "query-refund-status",
                    message,
                    "RULE_REFUND_STATUS"
            );
        }
        if (afterSalesRequestAnalysis.looksLikeRefundApply(message)) {
            return afterSalesDecision(
                    AfterSalesOperationEnum.APPLY,
                    "apply-refund",
                    message,
                    "RULE_REFUND_APPLY"
            );
        }
        if (orderRequestAnalysis.requiresUnsupportedOrderWrite(message)) {
            return RouteDecisionModel.clarify("RULE_ORDER_WRITE_UNSUPPORTED", List.of());
        }
        if (orderRequestAnalysis.requiresReActOrderResearch(message)) {
            return new RouteDecisionModel(
                    RouteTypeEnum.REACT,
                    REACT_EXECUTOR_ID,
                    "cross-tool-order-analysis",
                    List.of(),
                    "RULE_ORDER_REACT_ANALYSIS"
            );
        }
        if (orderRequestAnalysis.looksLikeOrderDiagnosis(message)) {
            return orderInquiryDecision(
                    OrderInquiryOperationEnum.DIAGNOSE,
                    "diagnose-order",
                    message,
                    "RULE_ORDER_DIAGNOSIS"
            );
        }
        if (orderRequestAnalysis.looksLikeOrderTracking(message)) {
            return orderInquiryDecision(
                    OrderInquiryOperationEnum.TRACK,
                    "track-order",
                    message,
                    "RULE_ORDER_TRACK"
            );
        }
        if (orderRequestAnalysis.looksLikeOrderQuery(message)) {
            return orderInquiryDecision(
                    OrderInquiryOperationEnum.QUERY,
                    "query-order",
                    message,
                    "RULE_ORDER_QUERY"
            );
        }

        try {
            RouteDecisionModel decision = decisionProvider.decide(
                    request,
                    userId,
                    history == null ? List.of() : List.copyOf(history),
                    cancellationToken
            );
            cancellationToken.throwIfCancelled();
            return normalize(decision);
        } catch (java.util.concurrent.CancellationException cancellation) {
            throw cancellation;
        } catch (RuntimeException invalidDecision) {
            return RouteDecisionModel.clarify("ROUTER_INVALID", List.of());
        }
    }

    private RouteDecisionModel normalize(RouteDecisionModel decision) {
        if (decision == null || decision.routeType() == null) {
            return RouteDecisionModel.clarify("ROUTER_EMPTY", List.of());
        }

        boolean supported = switch (decision.routeType()) {
            case ATOMIC -> CLOCK_EXECUTOR_ID.equals(decision.executorId());
            case WORKFLOW -> supportsWorkflow(decision);
            case REACT -> REACT_EXECUTOR_ID.equals(decision.executorId());
            case CLARIFY -> decision.executorId().isBlank();
        };
        if (!supported) {
            return RouteDecisionModel.clarify("ROUTER_UNSUPPORTED_EXECUTOR", List.of());
        }
        if (decision.routeType() != RouteTypeEnum.WORKFLOW || !decision.domainId().isBlank()) {
            return decision;
        }
        String domainId = workflows.find(decision.executorId())
                .map(executor -> executor.descriptor().domainId())
                .orElse("");
        return new RouteDecisionModel(
                decision.routeType(),
                domainId,
                decision.executorId(),
                decision.operation(),
                decision.normalizedIntent(),
                decision.parameters(),
                decision.requiredFields(),
                decision.reasonCode()
        );
    }

    private RouteDecisionModel orderInquiryDecision(
            OrderInquiryOperationEnum operation,
            String intent,
            String message,
            String reasonCode
    ) {
        String orderId = orderRequestAnalysis.extractOrderId(message);
        return new RouteDecisionModel(
                RouteTypeEnum.WORKFLOW,
                OrderInquiryWorkflow.DOMAIN_ID,
                OrderInquiryWorkflow.ID,
                operation.name(),
                intent,
                orderId == null ? Map.of() : Map.of("orderId", orderId),
                orderId == null ? List.of("orderId") : List.of(),
                reasonCode
        );
    }

    private RouteDecisionModel afterSalesDecision(
            AfterSalesOperationEnum operation,
            String intent,
            String message,
            String reasonCode
    ) {
        if (!workflows.contains(AfterSalesRefundWorkflow.ID)) {
            return RouteDecisionModel.clarify("RULE_AFTER_SALES_WORKFLOW_UNAVAILABLE", List.of());
        }
        String orderId = afterSalesRequestAnalysis.extractOrderId(message);
        RefundReasonEnum reason = afterSalesRequestAnalysis.extractReason(message);
        Map<String, String> parameters = new java.util.LinkedHashMap<>();
        if (orderId != null) {
            parameters.put("orderId", orderId);
        }
        if (reason != null) {
            parameters.put("refundReason", reason.name());
        }
        List<String> requiredFields = new java.util.ArrayList<>();
        if (orderId == null) {
            requiredFields.add("orderId");
        }
        if (operation == AfterSalesOperationEnum.APPLY && reason == null) {
            requiredFields.add("refundReason");
        }
        return new RouteDecisionModel(
                RouteTypeEnum.WORKFLOW,
                AfterSalesRefundWorkflow.DOMAIN_ID,
                AfterSalesRefundWorkflow.ID,
                operation.name(),
                intent,
                parameters,
                requiredFields,
                reasonCode
        );
    }

    private boolean supportsWorkflow(RouteDecisionModel decision) {
        return workflows.find(decision.executorId())
                .map(executor -> {
                    boolean domainMatches = decision.domainId().isBlank()
                            || decision.domainId().equals(executor.descriptor().domainId());
                    boolean operationSupported = decision.operation().isBlank()
                            || executor.descriptor().supportsOperation(decision.operation());
                    return domainMatches && operationSupported;
                })
                .orElse(false);
    }

    private boolean isClockRequest(String message) {
        return message.contains("现在几点")
                || message.equalsIgnoreCase("time")
                || message.contains("当前时间");
    }

}
