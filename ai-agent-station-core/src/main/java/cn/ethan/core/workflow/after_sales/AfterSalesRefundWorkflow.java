package cn.ethan.core.workflow.after_sales;

import cn.ethan.core.agent.model.AgentMemoryEntryModel;
import cn.ethan.core.agent.model.StructuredResultModel;
import cn.ethan.core.agent.support.CancellationToken;
import cn.ethan.core.after_sales.enums.AfterSalesCaseStatusEnum;
import cn.ethan.core.after_sales.enums.AfterSalesHandlingModeEnum;
import cn.ethan.core.after_sales.enums.AfterSalesOperationEnum;
import cn.ethan.core.after_sales.enums.RefundEligibilityEnum;
import cn.ethan.core.after_sales.enums.RefundReasonEnum;
import cn.ethan.core.after_sales.model.AfterSalesCaseModel;
import cn.ethan.core.after_sales.model.AfterSalesRefundSubmissionResultModel;
import cn.ethan.core.after_sales.model.RefundCommandModel;
import cn.ethan.core.after_sales.model.RefundCommandResultModel;
import cn.ethan.core.after_sales.model.RefundEligibilityModel;
import cn.ethan.core.after_sales.port.AfterSalesCaseGateway;
import cn.ethan.core.after_sales.port.AfterSalesRefundSubmissionGateway;
import cn.ethan.core.after_sales.port.RefundCommandGateway;
import cn.ethan.core.after_sales.service.AfterSalesRequestAnalysisService;
import cn.ethan.core.after_sales.service.RefundEligibilityService;
import cn.ethan.core.order.enums.OrderLookupStatusEnum;
import cn.ethan.core.order.model.OrderLookupResultModel;
import cn.ethan.core.order.model.OrderSnapshotModel;
import cn.ethan.core.order.model.RecentOrderModel;
import cn.ethan.core.order.port.OrderGateway;
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
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 售后退款流程：收集退款信息、创建售后申请，并在安全分支中执行最终退款命令。
 *
 * @author ethan
 * @date 2026-08-10
 */
public final class AfterSalesRefundWorkflow implements ResumableWorkflowExecutor {

    public static final String ID = "after-sales-refund";
    public static final String DOMAIN_ID = "after_sales";
    private static final String RESOLVE_ORDER = "resolve_order";
    private static final String QUERY_ORDER = "query_order";
    private static final String COLLECT_REASON = "collect_reason";
    private static final String COLLECT_DESCRIPTION = "collect_description";
    private static final String EVALUATE = "evaluate_refund";
    private static final String CONFIRM = "confirm_submission";

    private final OrderGateway orders;
    private final RefundCommandGateway refunds;
    private final AfterSalesCaseGateway cases;
    private final AfterSalesRefundSubmissionGateway automaticRefunds;
    private final WorkflowRunStore runs;
    private final WorkflowRunEventStore events;
    private final AfterSalesRequestAnalysisService requests;
    private final RefundEligibilityService eligibility;
    private final GraphExecutor executor;
    private final Clock clock;
    private final WorkflowDescriptorModel descriptor;
    private final WorkflowDefinitionModel definition;

    public AfterSalesRefundWorkflow(
            OrderGateway orders,
            RefundCommandGateway refunds,
            WorkflowRunStore runs,
            WorkflowRunEventStore events,
            AfterSalesRequestAnalysisService requests,
            RefundEligibilityService eligibility,
            GraphExecutor executor,
            Clock clock
    ) {
        this(orders, refunds, new NoOpAfterSalesCaseGateway(), runs, events, requests, eligibility, executor, clock);
    }

    public AfterSalesRefundWorkflow(
            OrderGateway orders,
            RefundCommandGateway refunds,
            AfterSalesCaseGateway cases,
            WorkflowRunStore runs,
            WorkflowRunEventStore events,
            AfterSalesRequestAnalysisService requests,
            RefundEligibilityService eligibility,
            GraphExecutor executor,
            Clock clock
    ) {
        this(orders, refunds, cases, directAutomaticRefunds(cases, refunds), runs, events,
                requests, eligibility, executor, clock);
    }

    public AfterSalesRefundWorkflow(
            OrderGateway orders,
            RefundCommandGateway refunds,
            AfterSalesCaseGateway cases,
            AfterSalesRefundSubmissionGateway automaticRefunds,
            WorkflowRunStore runs,
            WorkflowRunEventStore events,
            AfterSalesRequestAnalysisService requests,
            RefundEligibilityService eligibility,
            GraphExecutor executor,
            Clock clock
    ) {
        this.orders = orders;
        this.refunds = refunds;
        this.cases = cases;
        this.automaticRefunds = automaticRefunds;
        this.runs = runs;
        this.events = events;
        this.requests = requests;
        this.eligibility = eligibility;
        this.executor = executor;
        this.clock = clock;
        this.descriptor = new WorkflowDescriptorModel(
                DOMAIN_ID, ID, "v2", List.of(
                AfterSalesOperationEnum.APPLY.name(), AfterSalesOperationEnum.QUERY_STATUS.name()
        ));

        Map<String, WorkflowNode> nodes = new LinkedHashMap<>();
        nodes.put(RESOLVE_ORDER, this::resolveOrder);
        nodes.put(QUERY_ORDER, this::queryOrder);
        nodes.put("query_case_status", this::queryCaseStatus);
        nodes.put("check_existing_case", this::checkExistingCase);
        nodes.put(COLLECT_REASON, this::collectReason);
        nodes.put(COLLECT_DESCRIPTION, this::collectDescription);
        nodes.put(EVALUATE, this::evaluateRefund);
        nodes.put(CONFIRM, this::confirmSubmission);
        this.definition = new WorkflowDefinitionModel(ID, RESOLVE_ORDER, nodes, 14, 1);
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
                return result(request, run, cancellationToken);
            }
            throw new WorkflowRunConflictException("workflow run checkpoint or version has changed");
        }
        WorkflowAnswerSupport.validatePendingEnvelope(request, run, ID, DOMAIN_ID);

        Map<String, String> state = new LinkedHashMap<>(run.state());
        mergeAnswer(run.checkpointId(), request.answers(), state);
        state.put("lastAnswerRequestId", request.requestId());
        state.put("lastAnswerDigest", digest);
        if (CONFIRM.equals(run.checkpointId())) {
            return "REJECT".equals(state.get("decision"))
                    ? rejectSubmission(run, request, state, cancellationToken)
                    : submitConfirmed(run, request, state, cancellationToken);
        }
        WorkflowContextModel context = new WorkflowContextModel(
                new cn.ethan.core.agent.model.AgentRequestModel(
                        request.requestId(), request.sessionId(), "恢复售后退款流程"
                ), userId, cancellationToken, new LinkedHashMap<>(state)
        ).with("workflowRun", run).with("memorySuggestions", memorySuggestions);
        // 除订单选择外，统一从实时订单校验节点重新进入，避免将订单快照作为可恢复状态持久化。
        String resumeNode = RESOLVE_ORDER.equals(run.checkpointId()) ? RESOLVE_ORDER : QUERY_ORDER;
        WorkflowResultModel flow = executor.execute(definition, context, resumeNode);
        if (flow.status() == cn.ethan.core.workflow.enums.WorkflowStatusEnum.WAITING_USER_INPUT) {
            return flow;
        }
        return finish(run, request, flow, context,
                Boolean.TRUE.equals(flow.context().value("refundRejected"))
                        ? WorkflowRunStatusEnum.REJECTED : WorkflowRunStatusEnum.COMPLETED);
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
        String orderId = orderId(context);
        if (orderId == null) {
            List<String> options = orders.listRecentOrders(context.userId(), 5).stream()
                    .map(RecentOrderModel::orderId).toList();
            WorkflowQuestionFieldTypeEnum type = options.isEmpty()
                    ? WorkflowQuestionFieldTypeEnum.TEXT : WorkflowQuestionFieldTypeEnum.SINGLE_SELECT;
            return ask(context, RESOLVE_ORDER, "order_id_question", "选择订单",
                    options.isEmpty() ? "请提供订单号，例如 ORDER-PAID-001。" : "请选择需要处理的订单。",
                    List.of(new WorkflowQuestionFieldModel(
                            "orderId", "订单号", type, true, options, suggestion(context, "order.id")
                    )));
        }
        return NodeResultModel.continueTo(
                context.with("orderId", orderId).with("operation", operation(context).name()), QUERY_ORDER
        );
    }

    private NodeResultModel queryOrder(WorkflowContextModel context) {
        OrderLookupResultModel lookup = orders.findOrder(orderId(context), context.userId());
        return switch (lookup.status()) {
            case FOUND -> NodeResultModel.continueTo(
                    context.with("order", lookup.order()),
                    operation(context) == AfterSalesOperationEnum.QUERY_STATUS
                            ? "query_case_status" : "check_existing_case"
            );
            case NOT_FOUND -> NodeResultModel.complete(context, "未找到该订单，请检查订单号。");
            case ACCESS_DENIED -> NodeResultModel.complete(context, "无权对该订单发起售后申请。");
            case TEMPORARY_FAILURE -> NodeResultModel.failed(context, "订单服务暂时不可用，请稍后重试。");
        };
    }

    private NodeResultModel queryCaseStatus(WorkflowContextModel context) {
        Optional<AfterSalesCaseModel> caseModel = cases.findByOrder(orderId(context), context.userId());
        if (caseModel.isEmpty()) {
            return NodeResultModel.complete(context.with("structuredResult", new StructuredResultModel(
                    "1", "after_sales_status", Map.of("orderId", orderId(context), "status", "NOT_FOUND")
            )), "该订单暂无售后申请记录。");
        }
        return completeCase(context, caseModel.orElseThrow(), "已查询到该订单的售后申请状态。");
    }

    private NodeResultModel checkExistingCase(WorkflowContextModel context) {
        Optional<AfterSalesCaseModel> existing = cases.findByOrder(orderId(context), context.userId());
        return existing.map(caseModel -> completeCase(context, caseModel, "该订单已有售后申请，不会重复提交。"))
                .orElseGet(() -> NodeResultModel.continueTo(context, COLLECT_REASON));
    }

    private NodeResultModel collectReason(WorkflowContextModel context) {
        RefundReasonEnum reason = reason(context);
        if (reason == null) {
            return ask(context, COLLECT_REASON, "refund_reason_question", "选择退款原因",
                    "请说明退款原因。", List.of(new WorkflowQuestionFieldModel(
                            "refundReason", "退款原因", WorkflowQuestionFieldTypeEnum.SINGLE_SELECT, true,
                            List.of(
                                    RefundReasonEnum.DAMAGED.name(), RefundReasonEnum.NOT_RECEIVED.name(),
                                    RefundReasonEnum.QUALITY_ISSUE.name(), RefundReasonEnum.OTHER.name()
                            ), suggestion(context, "refund.reason")
                    )));
        }
        return requiresDescription(reason) && description(context) == null
                ? NodeResultModel.continueTo(context.with("refundReason", reason.name()), COLLECT_DESCRIPTION)
                : NodeResultModel.continueTo(context.with("refundReason", reason.name()), EVALUATE);
    }

    private NodeResultModel collectDescription(WorkflowContextModel context) {
        String description = description(context);
        if (description == null) {
            return ask(context, COLLECT_DESCRIPTION, "refund_description_question", "补充退款说明",
                    "请用 10 到 500 个字符说明问题，以便后续处理。", List.of(new WorkflowQuestionFieldModel(
                            "description", "问题说明", WorkflowQuestionFieldTypeEnum.TEXT, true, List.of()
                    )));
        }
        return NodeResultModel.continueTo(context.with("description", description), EVALUATE);
    }

    private NodeResultModel evaluateRefund(WorkflowContextModel context) {
        RefundEligibilityModel evaluated = eligibility.evaluate((OrderSnapshotModel) context.value("order"));
        if (evaluated.decision() == RefundEligibilityEnum.REJECTED) {
            return NodeResultModel.complete(context.with("structuredResult", new StructuredResultModel(
                    "1", "after_sales_rejected", Map.of("orderId", orderId(context), "decision", "REJECTED")
            )).with("refundRejected", true), evaluated.message());
        }
        AfterSalesHandlingModeEnum mode = evaluated.decision() == RefundEligibilityEnum.APPROVED
                ? AfterSalesHandlingModeEnum.AUTO_REFUND : AfterSalesHandlingModeEnum.MANUAL_REVIEW;
        Map<String, String> state = state(context);
        state.put("handlingMode", mode.name());
        if (evaluated.refundAmount() != null) {
            state.put("refundAmount", evaluated.refundAmount().toPlainString());
        }
        if (evaluated.currency() != null) {
            state.put("currency", evaluated.currency());
        }
        WorkflowContextModel enriched = context;
        for (Map.Entry<String, String> entry : state.entrySet()) {
            enriched = enriched.with(entry.getKey(), entry.getValue());
        }
        return NodeResultModel.continueTo(enriched, CONFIRM);
    }

    private NodeResultModel confirmSubmission(WorkflowContextModel context) {
        String mode = value(context, "handlingMode");
        String amount = value(context, "refundAmount");
        WorkflowQuestionModel question = new WorkflowQuestionModel(
                UUID.randomUUID().toString(), CONFIRM, "after_sales_confirmation", "确认售后申请",
                AfterSalesHandlingModeEnum.AUTO_REFUND.name().equals(mode)
                        ? "该申请将创建退款命令，请确认。" : "该申请将提交人工审核，请确认。",
                List.of(new WorkflowQuestionFieldModel(
                        "decision", "确认提交", WorkflowQuestionFieldTypeEnum.CONFIRM, true,
                        List.of("CONFIRM", "REJECT")
                ))
        );
        WorkflowRunModel run = persistQuestion(context, CONFIRM, question, operation(context));
        StructuredResultModel card = new StructuredResultModel("1", "after_sales_confirmation", Map.of(
                "runId", run.runId(), "checkpointId", run.checkpointId(), "questionId", question.questionId(),
                "version", run.version(), "orderId", orderId(context), "refundReason", value(context, "refundReason"),
                "handlingMode", mode, "refundAmount", amount == null ? "" : amount,
                "currency", value(context, "currency") == null ? "" : value(context, "currency")
        ));
        return NodeResultModel.waitingUserInput(
                context.with("workflowRun", run).with("workflowQuestion", question).with("structuredResult", card),
                CONFIRM, question.prompt(), question
        );
    }

    private WorkflowResultModel rejectSubmission(
            WorkflowRunModel run,
            WorkflowAnswerRequestModel request,
            Map<String, String> state,
            CancellationToken cancellationToken
    ) {
        return terminal(run, request, WorkflowRunStatusEnum.REJECTED, state,
                "已取消售后申请，不会创建退款命令。", cancellationToken);
    }

    private WorkflowResultModel submitConfirmed(
            WorkflowRunModel run,
            WorkflowAnswerRequestModel request,
            Map<String, String> state,
            CancellationToken cancellationToken
    ) {
        cancellationToken.throwIfCancelled();
        OrderLookupResultModel lookup = orders.findOrder(state.get("orderId"), run.userId());
        if (lookup.status() != OrderLookupStatusEnum.FOUND) {
            return terminal(run, request, WorkflowRunStatusEnum.REJECTED, state,
                    lookup.status() == OrderLookupStatusEnum.ACCESS_DENIED
                            ? "无权对该订单发起售后申请。" : "订单已不可用，未提交售后申请。",
                    cancellationToken);
        }
        Optional<AfterSalesCaseModel> existing = cases.findByOrder(state.get("orderId"), run.userId());
        if (existing.isPresent()) {
            return completeExistingCase(run, request, state, existing.orElseThrow(), cancellationToken);
        }
        RefundEligibilityModel evaluated = eligibility.evaluate(lookup.order());
        if (evaluated.decision() == RefundEligibilityEnum.REJECTED) {
            return terminal(run, request, WorkflowRunStatusEnum.REJECTED, state,
                    evaluated.message(), cancellationToken);
        }
        RefundReasonEnum reason = parseReason(state.get("refundReason"));
        if (reason == null) {
            throw new WorkflowRunConflictException("refundReason answer is invalid");
        }
        AfterSalesHandlingModeEnum mode = evaluated.decision() == RefundEligibilityEnum.APPROVED
                ? AfterSalesHandlingModeEnum.AUTO_REFUND : AfterSalesHandlingModeEnum.MANUAL_REVIEW;
        Instant now = clock.instant();
        AfterSalesCaseModel candidate = new AfterSalesCaseModel(
                UUID.randomUUID().toString(), run.runId(), run.userId(), lookup.order().orderId(), reason,
                state.getOrDefault("description", ""), mode,
                mode == AfterSalesHandlingModeEnum.AUTO_REFUND
                        ? AfterSalesCaseStatusEnum.REFUND_PROCESSING : AfterSalesCaseStatusEnum.PENDING_REVIEW,
                evaluated.refundAmount(), evaluated.currency(), "", 0, now, now
        );
        AfterSalesCaseModel created;
        RefundCommandResultModel refund = null;
        if (mode == AfterSalesHandlingModeEnum.AUTO_REFUND) {
            AfterSalesRefundSubmissionResultModel submitted = automaticRefunds.submit(candidate,
                    new RefundCommandModel(
                            run.runId(), candidate.caseId(), candidate.orderId(), candidate.userId(), candidate.reason(),
                            candidate.amount(), candidate.currency(), now
                    ));
            created = submitted.caseModel();
            refund = submitted.refundCommand();
        } else {
            created = cases.create(candidate);
        }
        if (!run.runId().equals(created.workflowRunId())) {
            return completeExistingCase(run, request, state, created, cancellationToken);
        }
        state.put("caseId", created.caseId());
        state.put("handlingMode", created.handlingMode().name());
        if (created.amount() != null) {
            state.put("refundAmount", created.amount().toPlainString());
        }
        if (!created.currency().isBlank()) {
            state.put("currency", created.currency());
        }
        if (created.handlingMode() == AfterSalesHandlingModeEnum.AUTO_REFUND) {
            if (refund == null) {
                refund = refunds.findByCaseId(created.caseId())
                        .orElseThrow(() -> new WorkflowRunConflictException("refund command is unavailable"));
            }
            state.put("refundId", refund.refundId());
        }
        String message = created.handlingMode() == AfterSalesHandlingModeEnum.AUTO_REFUND
                ? "售后申请已提交，退款正在处理中。" : "售后申请已提交，等待人工审核。";
        return terminal(run, request, WorkflowRunStatusEnum.COMPLETED, state, message, cancellationToken);
    }

    private WorkflowResultModel completeExistingCase(
            WorkflowRunModel run,
            WorkflowAnswerRequestModel request,
            Map<String, String> state,
            AfterSalesCaseModel caseModel,
            CancellationToken cancellationToken
    ) {
        state.put("caseId", caseModel.caseId());
        state.put("handlingMode", caseModel.handlingMode().name());
        if (!caseModel.refundId().isBlank()) {
            state.put("refundId", caseModel.refundId());
        }
        return terminal(run, request, WorkflowRunStatusEnum.COMPLETED, state,
                "该订单已有售后申请，不会重复提交。", cancellationToken);
    }

    private WorkflowResultModel terminal(
            WorkflowRunModel run,
            WorkflowAnswerRequestModel request,
            WorkflowRunStatusEnum target,
            Map<String, String> state,
            String content,
            CancellationToken cancellationToken
    ) {
        Instant now = clock.instant();
        WorkflowRunModel updated = run.next(target, run.checkpointId(), state, null, content, now);
        if (!runs.compareAndSet(run, updated)) {
            throw new WorkflowRunConflictException("workflow run version has changed");
        }
        events.append(event(updated, target == WorkflowRunStatusEnum.COMPLETED
                ? "WORKFLOW_COMPLETED" : "WORKFLOW_REJECTED", now));
        return result(request, updated, cancellationToken);
    }

    private NodeResultModel completeCase(WorkflowContextModel context, AfterSalesCaseModel caseModel, String message) {
        return NodeResultModel.complete(context.with("structuredResult", caseCard(caseModel)), message);
    }

    private NodeResultModel ask(
            WorkflowContextModel context,
            String checkpointId,
            String cardType,
            String title,
            String prompt,
            List<WorkflowQuestionFieldModel> fields
    ) {
        WorkflowQuestionModel question = new WorkflowQuestionModel(
                UUID.randomUUID().toString(), checkpointId, cardType, title, prompt, fields
        );
        WorkflowRunModel run = persistQuestion(context, checkpointId, question, operation(context));
        return NodeResultModel.waitingUserInput(
                context.with("workflowRun", run).with("workflowQuestion", question), checkpointId, prompt, question
        );
    }

    private WorkflowResultModel finish(
            WorkflowRunModel run,
            WorkflowAnswerRequestModel request,
            WorkflowResultModel flow,
            WorkflowContextModel fallback,
            WorkflowRunStatusEnum target
    ) {
        WorkflowContextModel context = flow.context() == null ? fallback : flow.context();
        return terminal(run, request, target, state(context), flow.content(), context.cancellationToken());
    }

    private WorkflowResultModel result(
            WorkflowAnswerRequestModel request,
            WorkflowRunModel run,
            CancellationToken cancellationToken
    ) {
        StructuredResultModel card = new StructuredResultModel("1", "after_sales_result", Map.of(
                "runId", run.runId(), "orderId", run.state().getOrDefault("orderId", ""),
                "status", run.status().name(), "caseId", run.state().getOrDefault("caseId", ""),
                "refundId", run.state().getOrDefault("refundId", ""),
                "handlingMode", run.state().getOrDefault("handlingMode", "")
        ));
        WorkflowContextModel context = new WorkflowContextModel(
                new cn.ethan.core.agent.model.AgentRequestModel(request.requestId(), request.sessionId(), "查询售后结果"),
                run.userId(), cancellationToken, Map.of("workflowRun", run, "structuredResult", card)
        );
        return WorkflowResultModel.completed(ID, run.resultContent(), card, context);
    }

    private WorkflowRunModel persistQuestion(
            WorkflowContextModel context,
            String checkpointId,
            WorkflowQuestionModel question,
            AfterSalesOperationEnum operation
    ) {
        Instant now = clock.instant();
        Map<String, String> state = state(context);
        state.put("operation", operation.name());
        Object current = context.value("workflowRun");
        if (current instanceof WorkflowRunModel existing) {
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
        if (RESOLVE_ORDER.equals(checkpointId)) {
            String orderId = requests.extractOrderId(answers.get("orderId"));
            if (orderId == null) {
                throw new WorkflowRunConflictException("orderId answer is invalid");
            }
            state.put("orderId", orderId);
            return;
        }
        if (COLLECT_REASON.equals(checkpointId)) {
            try {
                state.put("refundReason", RefundReasonEnum.valueOf(answers.get("refundReason")).name());
            } catch (RuntimeException invalid) {
                throw new WorkflowRunConflictException("refundReason answer is invalid");
            }
            return;
        }
        if (COLLECT_DESCRIPTION.equals(checkpointId)) {
            String description = answers.get("description");
            if (description == null || description.strip().length() < 10 || description.strip().length() > 500) {
                throw new WorkflowRunConflictException("description answer is invalid");
            }
            state.put("description", description.strip());
            return;
        }
        if (CONFIRM.equals(checkpointId)) {
            String decision = answers.get("decision");
            if (!"CONFIRM".equals(decision) && !"REJECT".equals(decision)) {
                throw new WorkflowRunConflictException("confirmation answer is invalid");
            }
            state.put("decision", decision);
            return;
        }
        throw new WorkflowRunConflictException("workflow run checkpoint is unsupported");
    }

    private AfterSalesOperationEnum operation(WorkflowContextModel context) {
        String operation = value(context, "operation");
        try {
            return operation == null ? (requests.looksLikeRefundStatus(context.request().message())
                    ? AfterSalesOperationEnum.QUERY_STATUS : AfterSalesOperationEnum.APPLY)
                    : AfterSalesOperationEnum.valueOf(operation);
        } catch (IllegalArgumentException invalid) {
            return AfterSalesOperationEnum.APPLY;
        }
    }

    private String orderId(WorkflowContextModel context) {
        String candidate = value(context, "orderId");
        return candidate == null ? requests.extractOrderId(context.request().message()) : requests.extractOrderId(candidate);
    }

    private RefundReasonEnum reason(WorkflowContextModel context) {
        String candidate = value(context, "refundReason");
        if (candidate != null) {
            try {
                return RefundReasonEnum.valueOf(candidate);
            } catch (IllegalArgumentException invalidReason) {
                return null;
            }
        }
        return requests.extractReason(context.request().message());
    }

    private RefundReasonEnum parseReason(String value) {
        try {
            return value == null ? null : RefundReasonEnum.valueOf(value);
        } catch (IllegalArgumentException invalidReason) {
            return null;
        }
    }

    private String description(WorkflowContextModel context) {
        String candidate = value(context, "description");
        return candidate != null && candidate.length() >= 10 && candidate.length() <= 500 ? candidate : null;
    }

    private boolean requiresDescription(RefundReasonEnum reason) {
        return reason == RefundReasonEnum.DAMAGED || reason == RefundReasonEnum.QUALITY_ISSUE
                || reason == RefundReasonEnum.OTHER;
    }

    private Map<String, String> state(WorkflowContextModel context) {
        Map<String, String> state = new LinkedHashMap<>();
        for (String key : List.of(
                "operation", "orderId", "refundReason", "description", "handlingMode", "refundAmount",
                "currency", "caseId", "refundId", "decision", "lastAnswerRequestId", "lastAnswerDigest"
        )) {
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

    private StructuredResultModel caseCard(AfterSalesCaseModel caseModel) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("caseId", caseModel.caseId());
        data.put("orderId", caseModel.orderId());
        data.put("status", caseModel.status().name());
        data.put("handlingMode", caseModel.handlingMode().name());
        data.put("refundId", caseModel.refundId());
        data.put("amount", caseModel.amount() == null ? "" : caseModel.amount());
        data.put("currency", caseModel.currency());
        data.put("failureCode", caseModel.failureCode());
        refunds.findByCaseId(caseModel.caseId()).ifPresent(command -> {
            data.put("refundCommandStatus", command.status());
            data.put("attemptCount", command.attemptCount());
            data.put("refundFailureCode", command.failureCode());
        });
        return new StructuredResultModel("1", "after_sales_status", data);
    }

    private static AfterSalesRefundSubmissionGateway directAutomaticRefunds(
            AfterSalesCaseGateway cases,
            RefundCommandGateway refunds
    ) {
        return (caseModel, command) -> {
            AfterSalesCaseModel created = cases.create(caseModel);
            if (!created.workflowRunId().equals(caseModel.workflowRunId())) {
                return new AfterSalesRefundSubmissionResultModel(
                        created,
                        refunds.findByCaseId(created.caseId()).orElse(null)
                );
            }
            RefundCommandResultModel refund = refunds.create(command);
            AfterSalesCaseModel updated = created.withRefund(refund.refundId(), command.createdAt());
            if (!cases.update(created, updated)) {
                throw new WorkflowRunConflictException("after-sales case version has changed");
            }
            return new AfterSalesRefundSubmissionResultModel(updated, refund);
        };
    }

    private WorkflowRunEventModel event(WorkflowRunModel run, String type, Instant now) {
        return new WorkflowRunEventModel(run.runId(), run.version(), type, run.status(), run.checkpointId(), now);
    }

    private String value(WorkflowContextModel context, String key) {
        Object value = context.value(key);
        return value instanceof String text && !text.isBlank() ? text.strip() : null;
    }

    private static final class NoOpAfterSalesCaseGateway implements AfterSalesCaseGateway {
        @Override
        public Optional<AfterSalesCaseModel> findByOrder(String orderId, String userId) {
            return Optional.empty();
        }

        @Override
        public Optional<AfterSalesCaseModel> findByWorkflowRunId(String workflowRunId) {
            return Optional.empty();
        }

        @Override
        public AfterSalesCaseModel create(AfterSalesCaseModel caseModel) {
            return caseModel;
        }

        @Override
        public boolean update(AfterSalesCaseModel expected, AfterSalesCaseModel updated) {
            return true;
        }
    }
}
