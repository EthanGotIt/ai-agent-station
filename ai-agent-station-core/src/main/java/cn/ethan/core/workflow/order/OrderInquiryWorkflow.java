package cn.ethan.core.workflow.order;

import cn.ethan.core.agent.model.AgentMemoryEntryModel;
import cn.ethan.core.agent.model.StructuredResultModel;
import cn.ethan.core.agent.support.CancellationToken;
import cn.ethan.core.order.enums.OrderDiagnosisTypeEnum;
import cn.ethan.core.order.enums.OrderInquiryOperationEnum;
import cn.ethan.core.order.enums.OrderIssueTypeEnum;
import cn.ethan.core.order.enums.OrderLookupStatusEnum;
import cn.ethan.core.order.enums.OrderStatusEnum;
import cn.ethan.core.order.model.LogisticsEventModel;
import cn.ethan.core.order.model.OrderItemModel;
import cn.ethan.core.order.model.OrderLookupResultModel;
import cn.ethan.core.order.model.OrderSnapshotModel;
import cn.ethan.core.order.model.RecentOrderModel;
import cn.ethan.core.order.port.LogisticsGateway;
import cn.ethan.core.order.port.OrderGateway;
import cn.ethan.core.order.service.OrderRequestAnalysisService;
import cn.ethan.core.workflow.engine.GraphExecutor;
import cn.ethan.core.workflow.enums.WorkflowQuestionFieldTypeEnum;
import cn.ethan.core.workflow.enums.WorkflowRunStatusEnum;
import cn.ethan.core.workflow.exception.WorkflowRunConflictException;
import cn.ethan.core.workflow.exception.WorkflowRunNotFoundException;
import cn.ethan.core.workflow.model.NodeResultModel;
import cn.ethan.core.workflow.model.WorkflowAnswerRequestModel;
import cn.ethan.core.workflow.model.WorkflowContextModel;
import cn.ethan.core.workflow.model.WorkflowDefinitionModel;
import cn.ethan.core.workflow.model.WorkflowDescriptorModel;
import cn.ethan.core.workflow.model.WorkflowQuestionFieldModel;
import cn.ethan.core.workflow.model.WorkflowQuestionModel;
import cn.ethan.core.workflow.model.WorkflowQuestionSuggestionModel;
import cn.ethan.core.workflow.model.WorkflowResultModel;
import cn.ethan.core.workflow.model.WorkflowRunEventModel;
import cn.ethan.core.workflow.model.WorkflowRunModel;
import cn.ethan.core.workflow.node.WorkflowNode;
import cn.ethan.core.workflow.port.ResumableWorkflowExecutor;
import cn.ethan.core.workflow.port.WorkflowRunEventStore;
import cn.ethan.core.workflow.port.WorkflowRunStore;
import cn.ethan.core.workflow.support.NoOpWorkflowRunStore;
import cn.ethan.core.workflow.support.WorkflowAnswerSupport;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 订单查询领域流程：编排订单详情、物流追踪和可恢复的确定性履约诊断。
 *
 * @author ethan
 * @date 2026-08-10
 */
public final class OrderInquiryWorkflow implements ResumableWorkflowExecutor {

    public static final String ID = "order-inquiry";
    public static final String DOMAIN_ID = "order";
    public static final String EXTRACT_ORDER_ID_NODE_ID = "resolve_order";
    private static final String RESOLVE_ISSUE_NODE_ID = "resolve_issue";
    private static final String QUERY_ORDER_NODE_ID = "query_order";
    private static final String LOAD_LOGISTICS_NODE_ID = "load_logistics";

    private final OrderGateway orders;
    private final LogisticsGateway logistics;
    private final GraphExecutor executor;
    private final OrderRequestAnalysisService analysis;
    private final Clock clock;
    private final Duration shipmentDelayThreshold;
    private final Duration logisticsStallThreshold;
    private final WorkflowRunStore runs;
    private final WorkflowRunEventStore events;
    private final WorkflowDescriptorModel descriptor;
    private final WorkflowDefinitionModel definition;

    public OrderInquiryWorkflow(
            OrderGateway orders,
            GraphExecutor executor,
            OrderRequestAnalysisService analysis,
            Clock clock,
            Duration shipmentDelayThreshold,
            Duration logisticsStallThreshold
    ) {
        this(orders, (orderId, userId) -> List.of(), executor, analysis, clock,
                shipmentDelayThreshold, logisticsStallThreshold, new NoOpWorkflowRunStore(), event -> { });
    }

    public OrderInquiryWorkflow(
            OrderGateway orders,
            GraphExecutor executor,
            OrderRequestAnalysisService analysis,
            Clock clock,
            Duration shipmentDelayThreshold,
            Duration logisticsStallThreshold,
            WorkflowRunStore runs,
            WorkflowRunEventStore events
    ) {
        this(orders, (orderId, userId) -> List.of(), executor, analysis, clock,
                shipmentDelayThreshold, logisticsStallThreshold, runs, events);
    }

    public OrderInquiryWorkflow(
            OrderGateway orders,
            LogisticsGateway logistics,
            GraphExecutor executor,
            OrderRequestAnalysisService analysis,
            Clock clock,
            Duration shipmentDelayThreshold,
            Duration logisticsStallThreshold,
            WorkflowRunStore runs,
            WorkflowRunEventStore events
    ) {
        this.orders = orders;
        this.logistics = logistics;
        this.executor = executor;
        this.analysis = analysis;
        this.clock = clock;
        this.shipmentDelayThreshold = shipmentDelayThreshold;
        this.logisticsStallThreshold = logisticsStallThreshold;
        this.runs = runs;
        this.events = events;
        this.descriptor = new WorkflowDescriptorModel(
                DOMAIN_ID, ID, "v2", List.of(
                OrderInquiryOperationEnum.QUERY.name(),
                OrderInquiryOperationEnum.TRACK.name(),
                OrderInquiryOperationEnum.DIAGNOSE.name()
        ));

        Map<String, WorkflowNode> nodes = new LinkedHashMap<>();
        nodes.put(EXTRACT_ORDER_ID_NODE_ID, this::resolveOrder);
        nodes.put(QUERY_ORDER_NODE_ID, this::queryOrder);
        nodes.put(RESOLVE_ISSUE_NODE_ID, this::resolveIssue);
        nodes.put(LOAD_LOGISTICS_NODE_ID, this::loadLogistics);
        nodes.put("diagnose_fulfillment", this::diagnoseFulfillment);
        nodes.put("format_query", this::formatQuery);
        nodes.put("format_tracking", this::formatTracking);
        nodes.put("format_diagnosis", this::formatDiagnosis);
        this.definition = new WorkflowDefinitionModel(ID, EXTRACT_ORDER_ID_NODE_ID, nodes, 12, 1);
    }

    @Override
    public String workflowId() {
        return ID;
    }

    @Override
    public WorkflowDescriptorModel descriptor() {
        return descriptor;
    }

    @Override
    public WorkflowResultModel execute(WorkflowContextModel context) {
        return executor.execute(definition, context);
    }

    @Override
    public WorkflowResultModel answer(
            WorkflowAnswerRequestModel request,
            String userId,
            CancellationToken cancellationToken,
            Map<String, Object> memorySuggestions
    ) {
        cancellationToken.throwIfCancelled();
        WorkflowRunModel run = runs.findOwned(request.runId(), userId, request.sessionId())
                .orElseThrow(() -> new WorkflowRunNotFoundException(request.runId()));
        if (!ID.equals(run.workflowId()) || !DOMAIN_ID.equals(run.domainId())) {
            throw new WorkflowRunNotFoundException(request.runId());
        }
        String digest = WorkflowAnswerSupport.answerDigest(request.answers());
        if (run.status() != WorkflowRunStatusEnum.WAITING_USER_INPUT) {
            if (request.requestId().equals(run.state().get("lastAnswerRequestId"))
                    && digest.equals(run.state().get("lastAnswerDigest"))) {
                return terminalResult(request, run, cancellationToken);
            }
            throw new WorkflowRunConflictException("workflow run checkpoint or version has changed");
        }
        WorkflowAnswerSupport.validatePendingEnvelope(request, run, ID, DOMAIN_ID);

        Map<String, String> state = new LinkedHashMap<>(run.state());
        mergeAnswer(run.checkpointId(), request.answers(), state);
        state.put("lastAnswerRequestId", request.requestId());
        state.put("lastAnswerDigest", digest);
        WorkflowContextModel context = new WorkflowContextModel(
                new cn.ethan.core.agent.model.AgentRequestModel(
                        request.requestId(), request.sessionId(), "恢复订单流程"
                ), userId, cancellationToken, new LinkedHashMap<>(state)
        ).with("workflowRun", run).with("memorySuggestions", memorySuggestions);
        // 从实时订单查询节点重入，避免把订单快照写入持久化运行状态。
        String resumeNode = EXTRACT_ORDER_ID_NODE_ID.equals(run.checkpointId())
                ? EXTRACT_ORDER_ID_NODE_ID : QUERY_ORDER_NODE_ID;
        WorkflowResultModel result = executor.execute(definition, context, resumeNode);
        if (result.status() == cn.ethan.core.workflow.enums.WorkflowStatusEnum.WAITING_USER_INPUT) {
            return result;
        }
        return finish(run, request, result, context);
    }

    @Override
    public WorkflowResultModel answer(
            WorkflowAnswerRequestModel request,
            String userId,
            CancellationToken cancellationToken
    ) {
        return answer(request, userId, cancellationToken, Map.of());
    }

    private NodeResultModel resolveOrder(WorkflowContextModel context) {
        OrderInquiryOperationEnum operation = operation(context);
        String orderId = orderId(context);
        OrderIssueTypeEnum inferredIssue = issue(context);
        if (inferredIssue == null) {
            inferredIssue = analysis.extractIssueType(context.request().message());
        }
        WorkflowContextModel enriched = inferredIssue == null ? context
                : context.with("issueType", inferredIssue.name());
        if (orderId == null) {
            List<RecentOrderModel> recent = orders.listRecentOrders(context.userId(), 5);
            List<String> options = recent.stream().map(RecentOrderModel::orderId).toList();
            WorkflowQuestionFieldTypeEnum type = options.isEmpty()
                    ? WorkflowQuestionFieldTypeEnum.TEXT : WorkflowQuestionFieldTypeEnum.SINGLE_SELECT;
            WorkflowQuestionModel question = new WorkflowQuestionModel(
                    UUID.randomUUID().toString(), EXTRACT_ORDER_ID_NODE_ID, "order_id_question",
                    "选择订单", options.isEmpty() ? "请提供订单号，例如 ORDER-001。"
                            : "请选择需要查询的近期订单。",
                    List.of(new WorkflowQuestionFieldModel(
                            "orderId", "订单号", type, true, options, suggestion(enriched, "order.id")
                    ))
            );
            WorkflowRunModel run = persistQuestion(enriched, EXTRACT_ORDER_ID_NODE_ID, question, operation);
            return NodeResultModel.waitingUserInput(
                    enriched.with("workflowQuestion", question).with("workflowRun", run),
                    EXTRACT_ORDER_ID_NODE_ID, question.prompt(), question
            );
        }
        return NodeResultModel.continueTo(
                enriched.with("orderId", orderId).with("operation", operation.name()), QUERY_ORDER_NODE_ID
        );
    }

    private NodeResultModel queryOrder(WorkflowContextModel context) {
        String orderId = orderId(context);
        OrderLookupResultModel result = orders.findOrder(orderId, context.userId());
        return switch (result.status()) {
            case FOUND -> switch (operation(context)) {
                case QUERY -> NodeResultModel.continueTo(context.with("order", result.order()), "format_query");
                case TRACK -> NodeResultModel.continueTo(context.with("order", result.order()), LOAD_LOGISTICS_NODE_ID);
                case DIAGNOSE -> NodeResultModel.continueTo(context.with("order", result.order()), RESOLVE_ISSUE_NODE_ID);
            };
            case NOT_FOUND -> NodeResultModel.complete(context, "未找到订单 " + orderId + "，请检查订单号是否正确。");
            case ACCESS_DENIED -> NodeResultModel.complete(context, "你无权查看订单 " + orderId + "。");
            case TEMPORARY_FAILURE -> NodeResultModel.failed(context, "订单服务暂时不可用，请稍后重试。");
        };
    }

    private NodeResultModel resolveIssue(WorkflowContextModel context) {
        OrderIssueTypeEnum issue = issue(context);
        if (issue == null) {
            WorkflowQuestionModel question = new WorkflowQuestionModel(
                    UUID.randomUUID().toString(), RESOLVE_ISSUE_NODE_ID, "order_issue_question",
                    "补充订单问题", "请选择当前最符合的订单问题。",
                    List.of(new WorkflowQuestionFieldModel(
                            "issueType", "问题类型", WorkflowQuestionFieldTypeEnum.SINGLE_SELECT, true,
                            List.of(
                                    OrderIssueTypeEnum.NOT_SHIPPED.name(),
                                    OrderIssueTypeEnum.LOGISTICS_STALLED.name(),
                                    OrderIssueTypeEnum.DELIVERY_OVERDUE.name(),
                                    OrderIssueTypeEnum.DELIVERED_NOT_RECEIVED.name(),
                                    OrderIssueTypeEnum.OTHER.name()
                            )
                    ))
            );
            WorkflowRunModel run = persistQuestion(context, RESOLVE_ISSUE_NODE_ID, question, operation(context));
            return NodeResultModel.waitingUserInput(
                    context.with("workflowQuestion", question).with("workflowRun", run),
                    RESOLVE_ISSUE_NODE_ID, question.prompt(), question
            );
        }
        return NodeResultModel.continueTo(context.with("issueType", issue.name()), LOAD_LOGISTICS_NODE_ID);
    }

    private NodeResultModel loadLogistics(WorkflowContextModel context) {
        List<LogisticsEventModel> trace = logistics.findTrace(orderId(context), context.userId());
        return NodeResultModel.continueTo(
                context.with("logistics", trace),
                operation(context) == OrderInquiryOperationEnum.TRACK ? "format_tracking" : "diagnose_fulfillment"
        );
    }

    private NodeResultModel diagnoseFulfillment(WorkflowContextModel context) {
        OrderSnapshotModel order = (OrderSnapshotModel) context.value("order");
        @SuppressWarnings("unchecked")
        List<LogisticsEventModel> trace = context.value("logistics") instanceof List<?> values
                ? (List<LogisticsEventModel>) values : List.of();
        OrderDiagnosisTypeEnum diagnosis = diagnose(order, issue(context), trace);
        return NodeResultModel.continueTo(context.with("diagnosis", diagnosis), "format_diagnosis");
    }

    private NodeResultModel formatQuery(WorkflowContextModel context) {
        OrderSnapshotModel order = (OrderSnapshotModel) context.value("order");
        List<OrderItemModel> items = orders.findItems(order.orderId(), context.userId());
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("orderId", order.orderId());
        fields.put("status", order.status().name());
        fields.put("paidAmount", order.paidAmount() == null ? "" : order.paidAmount());
        fields.put("currency", order.currency() == null ? "" : order.currency());
        fields.put("items", items);
        return NodeResultModel.complete(
                context.with("structuredResult", new StructuredResultModel("1", "order_overview", fields)),
                "订单 " + order.orderId() + " 当前状态：" + order.status() + "。"
        );
    }

    private NodeResultModel formatTracking(WorkflowContextModel context) {
        OrderSnapshotModel order = (OrderSnapshotModel) context.value("order");
        Object trace = context.value("logistics");
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("orderId", order.orderId());
        fields.put("status", order.status().name());
        fields.put("expectedDeliveryAt", order.expectedDeliveryAt() == null ? "" : order.expectedDeliveryAt());
        fields.put("events", trace == null ? List.of() : trace);
        return NodeResultModel.complete(
                context.with("structuredResult", new StructuredResultModel("1", "logistics_timeline", fields)),
                "订单 " + order.orderId() + " 的物流轨迹已更新。"
        );
    }

    private NodeResultModel formatDiagnosis(WorkflowContextModel context) {
        OrderSnapshotModel order = (OrderSnapshotModel) context.value("order");
        OrderDiagnosisTypeEnum diagnosis = (OrderDiagnosisTypeEnum) context.value("diagnosis");
        String recommendation = recommendation(diagnosis);
        return NodeResultModel.complete(
                context.with("structuredResult", new StructuredResultModel("1", "order_diagnosis", Map.of(
                        "orderId", order.orderId(), "status", order.status().name(),
                        "diagnosisType", diagnosis.name(), "issueType", issue(context).name(),
                        "recommendation", recommendation
                ))),
                compose(order, diagnosis) + " " + recommendation
        );
    }

    private WorkflowResultModel finish(
            WorkflowRunModel run,
            WorkflowAnswerRequestModel request,
            WorkflowResultModel result,
            WorkflowContextModel fallbackContext
    ) {
        WorkflowRunStatusEnum status = result.status() == cn.ethan.core.workflow.enums.WorkflowStatusEnum.COMPLETED
                ? WorkflowRunStatusEnum.COMPLETED : WorkflowRunStatusEnum.FAILED;
        WorkflowContextModel resultContext = result.context() == null ? fallbackContext : result.context();
        Instant now = clock.instant();
        WorkflowRunModel updated = run.next(
                status, run.checkpointId(), state(resultContext), null, result.content(), now
        );
        if (!runs.compareAndSet(run, updated)) {
            throw new WorkflowRunConflictException("workflow run version has changed");
        }
        events.append(new WorkflowRunEventModel(
                updated.runId(), updated.version(), status == WorkflowRunStatusEnum.COMPLETED
                ? "WORKFLOW_COMPLETED" : "WORKFLOW_FAILED", updated.status(), updated.checkpointId(), now
        ));
        WorkflowContextModel completed = resultContext.with("workflowRun", updated);
        return status == WorkflowRunStatusEnum.COMPLETED
                ? WorkflowResultModel.completed(ID, result.content(), result.structuredResult(), completed)
                : WorkflowResultModel.failed(ID, result.content(), completed);
    }

    private WorkflowResultModel terminalResult(
            WorkflowAnswerRequestModel request,
            WorkflowRunModel run,
            CancellationToken cancellationToken
    ) {
        StructuredResultModel card = new StructuredResultModel("1", "order_result", Map.of(
                "runId", run.runId(), "orderId", run.state().getOrDefault("orderId", ""),
                "status", run.status().name(), "operation", run.state().getOrDefault("operation", "")
        ));
        WorkflowContextModel context = new WorkflowContextModel(
                new cn.ethan.core.agent.model.AgentRequestModel(request.requestId(), request.sessionId(), "查询订单结果"),
                run.userId(), cancellationToken, Map.of("workflowRun", run, "structuredResult", card)
        );
        return WorkflowResultModel.completed(ID, run.resultContent(), card, context);
    }

    private WorkflowRunModel persistQuestion(
            WorkflowContextModel context,
            String checkpointId,
            WorkflowQuestionModel question,
            OrderInquiryOperationEnum operation
    ) {
        Instant now = clock.instant();
        Map<String, String> state = state(context);
        state.put("operation", operation.name());
        Object existingValue = context.value("workflowRun");
        if (existingValue instanceof WorkflowRunModel existing) {
            WorkflowRunModel updated = existing.next(
                    WorkflowRunStatusEnum.WAITING_USER_INPUT, checkpointId, state, question, "", now
            );
            if (!runs.compareAndSet(existing, updated)) {
                throw new WorkflowRunConflictException("workflow run version has changed");
            }
            events.append(event(updated, "WORKFLOW_QUESTIONED", now));
            return updated;
        }
        WorkflowRunModel created = new WorkflowRunModel(
                UUID.randomUUID().toString(), context.userId(), context.request().sessionId(), DOMAIN_ID, ID,
                descriptor.version(), operation.name(), WorkflowRunStatusEnum.WAITING_USER_INPUT, checkpointId,
                0, state, question, "", now, now
        );
        runs.create(created);
        events.append(event(created, "WORKFLOW_QUESTIONED", now));
        return created;
    }

    private void mergeAnswer(String checkpointId, Map<String, String> answers, Map<String, String> state) {
        if (EXTRACT_ORDER_ID_NODE_ID.equals(checkpointId)) {
            String orderId = analysis.extractOrderId(answers.get("orderId"));
            if (orderId == null) {
                throw new WorkflowRunConflictException("orderId answer is invalid");
            }
            state.put("orderId", orderId);
            return;
        }
        if (RESOLVE_ISSUE_NODE_ID.equals(checkpointId)) {
            try {
                state.put("issueType", OrderIssueTypeEnum.valueOf(answers.get("issueType")) .name());
            } catch (RuntimeException invalid) {
                throw new WorkflowRunConflictException("issueType answer is invalid");
            }
            return;
        }
        throw new WorkflowRunConflictException("workflow run checkpoint is unsupported");
    }

    private OrderInquiryOperationEnum operation(WorkflowContextModel context) {
        String value = value(context, "operation");
        try {
            return value == null ? (analysis.looksLikeOrderDiagnosis(context.request().message())
                    ? OrderInquiryOperationEnum.DIAGNOSE : analysis.looksLikeOrderTracking(context.request().message())
                    ? OrderInquiryOperationEnum.TRACK : OrderInquiryOperationEnum.QUERY)
                    : OrderInquiryOperationEnum.valueOf(value);
        } catch (IllegalArgumentException invalid) {
            return OrderInquiryOperationEnum.QUERY;
        }
    }

    private String orderId(WorkflowContextModel context) {
        String candidate = value(context, "orderId");
        return candidate == null ? analysis.extractOrderId(context.request().message()) : analysis.extractOrderId(candidate);
    }

    private OrderIssueTypeEnum issue(WorkflowContextModel context) {
        String candidate = value(context, "issueType");
        if (candidate == null) {
            return null;
        }
        try {
            return OrderIssueTypeEnum.valueOf(candidate);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private Map<String, String> state(WorkflowContextModel context) {
        Map<String, String> state = new LinkedHashMap<>();
        for (String key : List.of("operation", "orderId", "issueType", "lastAnswerRequestId", "lastAnswerDigest")) {
            String value = value(context, key);
            if (value != null) {
                state.put(key, value);
            }
        }
        return state;
    }

    private WorkflowQuestionSuggestionModel suggestion(WorkflowContextModel context, String memoryKey) {
        Object raw = context.value("memorySuggestions");
        if (!(raw instanceof Map<?, ?> suggestions) || !(suggestions.get(memoryKey) instanceof AgentMemoryEntryModel entry)) {
            return null;
        }
        return new WorkflowQuestionSuggestionModel(entry.value(), "MEMORY", entry.entryId());
    }

    private OrderDiagnosisTypeEnum diagnose(
            OrderSnapshotModel order,
            OrderIssueTypeEnum issue,
            List<LogisticsEventModel> trace
    ) {
        Instant now = clock.instant();
        if (issue == OrderIssueTypeEnum.DELIVERED_NOT_RECEIVED && order.status() == OrderStatusEnum.DELIVERED) {
            return OrderDiagnosisTypeEnum.DELIVERY_DISPUTE;
        }
        if (issue == OrderIssueTypeEnum.NOT_SHIPPED && order.status() == OrderStatusEnum.PAID) {
            return order.createdAt() == null ? OrderDiagnosisTypeEnum.INSUFFICIENT_DATA
                    : elapsedAtLeast(order.createdAt(), shipmentDelayThreshold, now)
                    ? OrderDiagnosisTypeEnum.SHIPMENT_DELAY : OrderDiagnosisTypeEnum.NO_ANOMALY;
        }
        if (issue == OrderIssueTypeEnum.DELIVERY_OVERDUE && order.status() == OrderStatusEnum.SHIPPED) {
            return order.expectedDeliveryAt() == null ? OrderDiagnosisTypeEnum.INSUFFICIENT_DATA
                    : now.isAfter(order.expectedDeliveryAt()) ? OrderDiagnosisTypeEnum.DELIVERY_OVERDUE
                    : OrderDiagnosisTypeEnum.NO_ANOMALY;
        }
        if (issue == OrderIssueTypeEnum.LOGISTICS_STALLED && order.status() == OrderStatusEnum.SHIPPED) {
            Instant latest = trace.isEmpty() ? order.lastLogisticsAt() : trace.get(trace.size() - 1).occurredAt();
            return latest == null ? OrderDiagnosisTypeEnum.INSUFFICIENT_DATA
                    : elapsedAtLeast(latest, logisticsStallThreshold, now)
                    ? OrderDiagnosisTypeEnum.LOGISTICS_STALLED : OrderDiagnosisTypeEnum.NO_ANOMALY;
        }
        if (order.status() == OrderStatusEnum.PAID) {
            return order.createdAt() == null ? OrderDiagnosisTypeEnum.INSUFFICIENT_DATA
                    : elapsedAtLeast(order.createdAt(), shipmentDelayThreshold, now)
                    ? OrderDiagnosisTypeEnum.SHIPMENT_DELAY : OrderDiagnosisTypeEnum.NO_ANOMALY;
        }
        return OrderDiagnosisTypeEnum.INSUFFICIENT_DATA;
    }

    private boolean elapsedAtLeast(Instant occurredAt, Duration threshold, Instant now) {
        return !now.isBefore(occurredAt.plus(threshold));
    }

    private String recommendation(OrderDiagnosisTypeEnum diagnosis) {
        return switch (diagnosis) {
            case SHIPMENT_DELAY -> "建议联系商家确认发货安排。";
            case DELIVERY_OVERDUE -> "建议核对物流轨迹并联系承运方。";
            case LOGISTICS_STALLED -> "建议提供物流停滞信息并等待进一步处理。";
            case DELIVERY_DISPUTE -> "建议先核对签收信息；需要售后时可发起退款申请。";
            case INSUFFICIENT_DATA -> "建议补充订单或物流信息后再判断。";
            case NO_ANOMALY -> "当前无需执行订单写操作。";
        };
    }

    private String compose(OrderSnapshotModel order, OrderDiagnosisTypeEnum diagnosis) {
        return switch (diagnosis) {
            case SHIPMENT_DELAY -> "订单 " + order.orderId() + " 存在发货延迟：已支付超过 "
                    + shipmentDelayThreshold.toHours() + " 小时仍未发货。";
            case DELIVERY_OVERDUE -> "订单 " + order.orderId() + " 已超过预计送达时间。";
            case LOGISTICS_STALLED -> "订单 " + order.orderId() + " 的物流超过 "
                    + logisticsStallThreshold.toHours() + " 小时未更新。";
            case DELIVERY_DISPUTE -> "订单 " + order.orderId() + " 显示已签收，但用户反馈未收到。";
            case INSUFFICIENT_DATA -> "订单 " + order.orderId() + " 的诊断数据不足。";
            case NO_ANOMALY -> "订单 " + order.orderId() + " 当前未发现可确认的履约异常。";
        };
    }

    private WorkflowRunEventModel event(WorkflowRunModel run, String type, Instant now) {
        return new WorkflowRunEventModel(run.runId(), run.version(), type, run.status(), run.checkpointId(), now);
    }

    private String value(WorkflowContextModel context, String key) {
        Object candidate = context.value(key);
        return candidate instanceof String text && !text.isBlank() ? text.strip() : null;
    }

}
