package cn.ethan.infrastructure.agent.workflow.transaction;

import cn.ethan.core.agent.action.ExternalActionCommandModel;
import cn.ethan.core.agent.action.ExternalActionCommandStore;
import cn.ethan.core.agent.action.ExternalActionStatusEnum;
import cn.ethan.core.agent.action.ExternalActionTypeEnum;
import cn.ethan.core.agent.event.AgentThreadEventGateway;
import cn.ethan.core.agent.thread.AgentItemModel;
import cn.ethan.core.agent.thread.AgentItemStore;
import cn.ethan.core.agent.thread.AgentItemTypeEnum;
import cn.ethan.core.agent.thread.AgentThreadConflictException;
import cn.ethan.core.agent.thread.AgentThreadModel;
import cn.ethan.core.agent.thread.AgentTurnModel;
import cn.ethan.core.agent.thread.AgentTurnStatusEnum;
import cn.ethan.core.agent.thread.AgentTurnStore;
import cn.ethan.core.agent.thread.AgentWorkflowAnswerInput;
import cn.ethan.core.agent.workflow.AgentWorkflowDecisionEnum;
import cn.ethan.core.agent.workflow.AgentWorkflowEngine;
import cn.ethan.core.agent.workflow.AgentWorkflowQuestionFieldModel;
import cn.ethan.core.agent.workflow.AgentWorkflowQuestionModel;
import cn.ethan.core.agent.workflow.AgentWorkflowAnswerActionEnum;
import cn.ethan.core.agent.workflow.AgentWorkflowQuestionStatusEnum;
import cn.ethan.core.agent.workflow.AgentWorkflowQuestionStore;
import cn.ethan.core.agent.workflow.AgentWorkflowRunModel;
import cn.ethan.core.agent.workflow.AgentWorkflowRunStore;
import cn.ethan.core.agent.workflow.AgentWorkflowStatusEnum;
import cn.ethan.core.agent.workflow.AgentWorkflowTypeEnum;
import cn.ethan.core.commerce.order.LogisticsEventModel;
import cn.ethan.core.commerce.order.LogisticsGateway;
import cn.ethan.core.commerce.order.OrderGateway;
import cn.ethan.core.commerce.order.OrderLookupResultModel;
import cn.ethan.core.commerce.order.OrderLookupStatusEnum;
import cn.ethan.core.commerce.order.OrderSearchCriteria;
import cn.ethan.core.commerce.order.OrderSearchResultModel;
import cn.ethan.core.commerce.order.OrderSearchStatusEnum;
import cn.ethan.core.commerce.order.OrderSnapshotModel;
import cn.ethan.core.commerce.order.OrderStatusEnum;
import cn.ethan.core.commerce.order.OrderVisibilityEnum;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 类型职责：以本地事务推进订单售后 Workflow，并把所有外部动作投影回 owner Turn。
 *
 * <p>模型只负责理解用户意图，候选筛选、QuestionCard 顺序、授权和幂等命令均由本引擎确定性处理。
 * 订单和物流查询在本地写事务之外完成；Question 关闭与下一张 Question 创建在同一事务内完成。</p>
 *
 * @author ethan
 * @date 2026-08-22
 */
@Component
public final class TransactionalAgentWorkflowEngine implements AgentWorkflowEngine {

    private static final String ORDER_SERVICE = "ORDER_SERVICE";
    private static final String REFUND = "REFUND";
    private static final String EXPEDITE = "EXPEDITE";
    private static final String ORDER_HISTORY = "ORDER_HISTORY";
    private static final String HIDE_ORDER = "HIDE_ORDER";
    private static final String RESTORE_ORDER = "RESTORE_ORDER";
    private static final int STEP_INTENT = 0;
    private static final int STEP_ORDER = 1;
    private static final int STEP_REASON = 2;
    private static final int STEP_CONFIRM = 3;
    private static final int STEP_HISTORY_ACTION = 4;
    private static final int MAX_REASON_LENGTH = 512;

    private final Clock clock;
    private final AgentWorkflowQuestionStore questions;
    private final ExternalActionCommandStore commands;
    private final ObjectMapper objectMapper;
    private final AgentWorkflowRunStore workflowRuns;
    private final OrderGateway orders;
    private final LogisticsGateway logistics;
    private final AgentItemStore items;
    private final AgentTurnStore turns;
    private final AgentThreadEventGateway events;
    private final TransactionTemplate transactionTemplate;

    /**
     * 测试替身构造器：保留无事务和无 owner 投影依赖的纯 Workflow 状态测试边界。
     */
    public TransactionalAgentWorkflowEngine(
            Clock clock,
            AgentWorkflowQuestionStore questions,
            ExternalActionCommandStore commands,
            ObjectMapper objectMapper,
            AgentWorkflowRunStore workflowRuns,
            OrderGateway orders,
            AgentItemStore items
    ) {
        this(clock, questions, commands, objectMapper, workflowRuns, orders, null, items,
                null, null, (TransactionTemplate) null);
    }

    @Autowired
    public TransactionalAgentWorkflowEngine(
            Clock clock,
            AgentWorkflowQuestionStore questions,
            ExternalActionCommandStore commands,
            ObjectMapper objectMapper,
            AgentWorkflowRunStore workflowRuns,
            OrderGateway orders,
            LogisticsGateway logistics,
            AgentItemStore items,
            AgentTurnStore turns,
            AgentThreadEventGateway events,
            PlatformTransactionManager transactionManager
    ) {
        this(clock, questions, commands, objectMapper, workflowRuns, orders, logistics, items,
                turns, events, transactionManager == null ? null : new TransactionTemplate(transactionManager));
    }

    private TransactionalAgentWorkflowEngine(
            Clock clock,
            AgentWorkflowQuestionStore questions,
            ExternalActionCommandStore commands,
            ObjectMapper objectMapper,
            AgentWorkflowRunStore workflowRuns,
            OrderGateway orders,
            LogisticsGateway logistics,
            AgentItemStore items,
            AgentTurnStore turns,
            AgentThreadEventGateway events,
            TransactionTemplate transactionTemplate
    ) {
        this.clock = clock;
        this.questions = questions;
        this.commands = commands;
        this.objectMapper = objectMapper;
        this.workflowRuns = workflowRuns;
        this.orders = orders;
        this.logistics = logistics;
        this.items = items;
        this.turns = turns;
        this.events = events;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public StartResult start(
            AgentThreadModel thread,
            AgentTurnModel turn,
            String operation,
            Map<String, String> arguments
    ) {
        WorkflowRequest request = WorkflowRequest.from(operation, arguments);
        ResolvedCandidates candidates = request.intent().isBlank() || ORDER_HISTORY.equals(request.intent())
                ? ResolvedCandidates.empty()
                : resolveCandidates(request, thread.userId());
        return inTransaction(() -> persistStart(thread, turn, request, candidates));
    }

    private StartResult persistStart(
            AgentThreadModel thread,
            AgentTurnModel turn,
            WorkflowRequest request,
            ResolvedCandidates candidates
    ) {
        Optional<AgentWorkflowQuestionModel> openQuestion =
                questions.findOpenQuestion(thread.userId(), thread.threadId());
        if (openQuestion.isPresent()) {
            AgentWorkflowRunModel existingRun = workflowRuns.find(thread.userId(), openQuestion.get().runId())
                    .orElseThrow(() -> new IllegalStateException("开放 QuestionCard 缺少 WorkflowRun"));
            if (existingRun.workflowType() == AgentWorkflowTypeEnum.ORDER_SERVICE
                    || legacyTypeMatches(existingRun.workflowType(), request.intent())) {
                return new StartResult(existingRun.runId(), openQuestion.get());
            }
            throw new AgentThreadConflictException("THREAD_WORKFLOW_ACTIVE", "当前 Thread 已有其他 Workflow 等待确认");
        }

        String runId = "workflow-" + UUID.randomUUID();
        Instant now = clock.instant();
        OrderSnapshotModel selected = selectCandidate(request, candidates);
        SelectedOrder selectedOrder = selected == null ? null : selectedOrder(thread.userId(), selected);
        WorkflowRequest effectiveRequest = selected == null
                ? request
                : request.withOrderId(selected.orderId());
        QuestionPlan plan = nextQuestion(effectiveRequest, candidates.orders(), selectedOrder);
        Map<String, Object> state = state(effectiveRequest, candidates.orders(), selectedOrder, null);
        AgentWorkflowRunModel run = new AgentWorkflowRunModel(
                runId, thread.threadId(), turn.turnId(), thread.userId(), AgentWorkflowTypeEnum.ORDER_SERVICE,
                AgentWorkflowStatusEnum.WAITING_USER_INPUT, 0L,
                steps(plan.stepName()), writeJson(state), now, now);
        AgentWorkflowQuestionModel question = question(runId, thread, turn, plan, now);

        workflowRuns.create(run);
        questions.saveQuestion(question);
        appendItem(thread, turn, AgentItemTypeEnum.WORKFLOW_STARTED, runId, now);
        appendOrderFacts(thread, turn, candidates.orders(), selectedOrder, now);
        appendItem(thread, turn, AgentItemTypeEnum.WORKFLOW_QUESTION, questionPayload(question), now);
        return new StartResult(runId, question);
    }

    @Override
    public ResumeResult resume(AgentThreadModel thread, AgentTurnModel turn, Map<String, String> ignoredAnswers) {
        ResumePreparation preparation = prepareResume(thread, turn);
        return inTransaction(() -> resumeInTransaction(thread, turn, preparation));
    }

    private ResumePreparation prepareResume(AgentThreadModel thread, AgentTurnModel turn) {
        AgentWorkflowAnswerInput answerInput = turn.workflowAnswerInput();
        if (answerInput == null) {
            throw new AgentThreadConflictException(
                    "WORKFLOW_ANSWER_INPUT_MISSING", "回答 Turn 缺少可恢复的结构化输入");
        }
        AgentWorkflowQuestionModel question = questions.findOpenQuestionByRun(thread.userId(), answerInput.runId())
                .orElseThrow(() -> new IllegalStateException("Workflow QuestionCard 不存在或已处理"));
        if (!question.questionId().equals(answerInput.questionId())
                || !question.checkpointId().equals(answerInput.checkpointId())
                || question.version() != answerInput.enqueuedQuestionVersion()
                || !turn.turnId().equals(question.answerTurnId())
                || question.answerEnqueueStatus()
                != AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum.ENQUEUED) {
            throw new AgentThreadConflictException(
                    "WORKFLOW_VERSION_CONFLICT", "QuestionCard 回答 Turn、检查点或入队版本已变化");
        }
        AgentWorkflowRunModel run = workflowRuns.find(thread.userId(), answerInput.runId())
                .orElseThrow(() -> new IllegalStateException("WorkflowRun 不存在或不属于当前用户"));
        if (answerInput.action() == AgentWorkflowAnswerActionEnum.CANCEL) {
            return new ResumePreparation(question, run, Map.of(), "CANCEL", null);
        }
        Map<String, String> validatedAnswers = question.validateAnswers(answerInput.answers());
        String step = questionStep(question);
        if (isLegacyQuestion(question, run)) {
            return new ResumePreparation(question, run, validatedAnswers, step, null);
        }
        WorkflowRequest request = requestFromState(run);
        if ("INTENT".equals(step)) {
            request = request.withIntent(normalizeIntent(validatedAnswers.get("intent")));
            ResolvedCandidates candidates = ORDER_HISTORY.equals(request.intent())
                    ? ResolvedCandidates.empty() : resolveCandidates(request, thread.userId());
            return new ResumePreparation(question, run, validatedAnswers, step,
                    new CandidatePreparation(request, candidates, null));
        }
        if ("HISTORY_ACTION".equals(step)) {
            String action = normalizeIntent(requiredAnswer(validatedAnswers, "historyAction"));
            WorkflowRequest actionRequest = request.withIntent(action);
            ResolvedCandidates candidates = resolveCandidates(actionRequest, thread.userId());
            return new ResumePreparation(question, run, validatedAnswers, step,
                    new CandidatePreparation(actionRequest, candidates, null));
        }
        if ("ORDER_SELECT".equals(step)) {
            String selectedId = requiredAnswer(validatedAnswers, "orderId");
            WorkflowRequest selectedRequest = request.withOrderId(selectedId);
            SelectedOrder selected = selectedOrder(thread.userId(), lookupSelected(selectedId, thread.userId()));
            return new ResumePreparation(question, run, validatedAnswers, step,
                    new CandidatePreparation(selectedRequest,
                            new ResolvedCandidates(List.of(selected.order()), true), selected));
        }
        if ("REASON".equals(step) || "CONFIRM".equals(step)) {
            String selectedId = requiredState(run, "orderId");
            SelectedOrder selected = selectedOrder(thread.userId(), lookupSelected(selectedId, thread.userId()));
            return new ResumePreparation(question, run, validatedAnswers, step,
                    new CandidatePreparation(request.withOrderId(selectedId),
                            new ResolvedCandidates(List.of(selected.order()), true), selected));
        }
        throw new AgentThreadConflictException("WORKFLOW_STEP_INVALID", "无法识别当前订单 Workflow 步骤");
    }

    private ResumeResult resumeInTransaction(
            AgentThreadModel thread,
            AgentTurnModel answerTurn,
            ResumePreparation preparation
    ) {
        AgentWorkflowQuestionModel question = preparation.question();
        AgentWorkflowRunModel run = preparation.run();
        Instant now = clock.instant();
        if (!questions.closeAnswerTurn(thread.userId(), question.questionId(),
                question.version(), answerTurn.turnId(), now)) {
            throw new AgentThreadConflictException(
                "WORKFLOW_VERSION_CONFLICT", "QuestionCard 回答 Turn、入队状态或版本已变化");
        }

        if ("CANCEL".equals(preparation.step())) {
            return cancel(thread, answerTurn, run, now);
        }

        if (isLegacyQuestion(question, run)) {
            return resumeLegacy(thread, answerTurn, run, question, preparation.answers(), now);
        }

        CandidatePreparation candidate = preparation.candidate();
        WorkflowRequest request = candidate.request();
        ResolvedCandidates candidates = candidate.candidates();
        SelectedOrder selected = candidate.selected();
        String step = preparation.step();

        if ("CONFIRM".equals(step)) {
            AgentWorkflowDecisionEnum decision = parseDecision(preparation.answers().get("decision"));
            if (decision == AgentWorkflowDecisionEnum.REJECT) {
                return reject(thread, answerTurn, run, request, now);
            }
            requireActionAllowed(request.intent(), selected.order());
            return approve(thread, answerTurn, run, request, selected, now);
        }

        String reason = "REASON".equals(step)
                ? bounded(preparation.answers().get("reason"), MAX_REASON_LENGTH)
                : request.reason();
        if ("REASON".equals(step)) {
            request = request.withReason(reason);
        }
        if (selected == null && candidates.orders().size() == 1) {
            selected = selectedOrder(thread.userId(), candidates.orders().get(0));
            request = request.withOrderId(selected.order().orderId());
        }
        QuestionPlan nextPlan = nextQuestion(request, candidates.orders(), selected);
        Map<String, Object> nextState = state(request, candidates.orders(), selected, reason);
        AgentWorkflowRunModel nextRun = run.progress(
                steps(nextPlan.stepName()), writeJson(nextState), now);
        workflowRuns.update(nextRun);
        AgentWorkflowQuestionModel nextQuestion = question(run.runId(), thread, threadOwnerTurn(thread, run), nextPlan, now);
        questions.saveQuestion(nextQuestion);
        appendAnswerResult(answerTurn, "WAITING_USER_INPUT", run.runId(), now);
        AgentTurnModel owner = threadOwnerTurn(thread, run);
        projectOwner(thread, run.runId(), AgentTurnStatusEnum.WAITING_USER_INPUT,
                null, "还需要你补充一项订单信息。", now);
        if (owner != null) {
            appendOrderFacts(thread, owner, candidates.orders(), selected, now);
        }
        appendOwnerQuestion(thread, run, nextQuestion, now);
        return new ResumeResult("我已核验订单信息，还需要你补充下一项内容。",
                "WAITING_USER_INPUT", null, nextQuestion);
    }

    private ResumeResult resumeLegacy(
            AgentThreadModel thread,
            AgentTurnModel answerTurn,
            AgentWorkflowRunModel run,
            AgentWorkflowQuestionModel question,
            Map<String, String> answers,
            Instant now
    ) {
        AgentWorkflowDecisionEnum decision = parseDecision(answers.get("decision"));
        if (decision == AgentWorkflowDecisionEnum.REJECT) {
            workflowRuns.update(run.status(AgentWorkflowStatusEnum.REJECTED,
                    steps("TERMINAL"), run.stateJson(), now));
            appendAnswerResult(answerTurn, "REJECTED", run.runId(), now);
            projectOwner(thread, run.runId(), AgentTurnStatusEnum.COMPLETED,
                    null, "本次操作已取消。", now);
            return new ResumeResult("已拒绝本次操作，Workflow 已安全结束。", "REJECTED", null);
        }
        try {
            JsonNode root = objectMapper.readTree(question.fieldsJson());
            String operation = root.path("operation").asString(run.workflowType().name());
            ExternalActionTypeEnum type = ExternalActionTypeEnum.valueOf(operation);
            return createAction(thread, answerTurn, run, type,
                    root.path("arguments").isObject() ? root.path("arguments") : objectMapper.createObjectNode(),
                    now, "已确认，外部动作已进入可靠执行队列。", run.stateJson());
        } catch (RuntimeException failure) {
            throw new IllegalStateException("无法创建兼容 Workflow 外部动作命令", failure);
        }
    }

    private ResumeResult reject(
            AgentThreadModel thread,
            AgentTurnModel answerTurn,
            AgentWorkflowRunModel run,
            WorkflowRequest request,
            Instant now
    ) {
        Map<String, Object> state = state(request, List.of(), null, request.reason());
        workflowRuns.update(run.status(AgentWorkflowStatusEnum.REJECTED,
                steps("TERMINAL"), writeJson(state), now));
        appendAnswerResult(answerTurn, "REJECTED", run.runId(), now);
        projectOwner(thread, run.runId(), AgentTurnStatusEnum.COMPLETED,
                null, "本次操作已取消。", now);
        return new ResumeResult("已取消本次订单操作，未产生外部副作用。", "REJECTED", null);
    }

    private ResumeResult cancel(
            AgentThreadModel thread,
            AgentTurnModel answerTurn,
            AgentWorkflowRunModel run,
            Instant now
    ) {
        workflowRuns.update(run.status(AgentWorkflowStatusEnum.REJECTED,
                steps("TERMINAL"), run.stateJson(), now));
        appendAnswerResult(answerTurn, "REJECTED", run.runId(), now);
        projectOwner(thread, run.runId(), AgentTurnStatusEnum.COMPLETED,
                null, "本次操作已结束，未执行外部动作。", now);
        return new ResumeResult("本次操作已结束，未执行外部动作。", "REJECTED", null);
    }

    private ResumeResult approve(
            AgentThreadModel thread,
            AgentTurnModel answerTurn,
            AgentWorkflowRunModel run,
            WorkflowRequest request,
            SelectedOrder selected,
            Instant now
    ) {
        ExternalActionTypeEnum actionType = actionType(request.intent());
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("orderId", selected.order().orderId());
        if (!request.reason().isBlank()) {
            payload.put("reason", request.reason());
        }
        if (HIDE_ORDER.equals(request.intent()) || RESTORE_ORDER.equals(request.intent())) {
            payload.put("visibility", HIDE_ORDER.equals(request.intent()) ? "HIDDEN" : "ACTIVE");
        }
        try {
            return createAction(thread, answerTurn, run, actionType,
                    objectMapper.readTree(writeJson(payload)), now,
                    "已确认，外部动作已进入可靠执行队列。",
                    writeJson(state(request.withOrderId(selected.order().orderId()),
                            List.of(selected.order()), selected, request.reason())));
        } catch (Exception failure) {
            throw new IllegalStateException("无法生成订单外部动作参数", failure);
        }
    }

    private ResumeResult createAction(
            AgentThreadModel thread,
            AgentTurnModel answerTurn,
            AgentWorkflowRunModel run,
            ExternalActionTypeEnum type,
            JsonNode payload,
            Instant now,
            String message,
            String nextStateJson
    ) {
        String orderId = payload.path("orderId").asString("").trim();
        if (orderId.isBlank()) {
            throw new AgentThreadConflictException("ORDER_REQUIRED", "外部动作缺少订单号");
        }
        String idempotencyKey = "order-service:" + run.runId() + ":" + type.name() + ":" + orderId;
        ExternalActionCommandModel command = new ExternalActionCommandModel(
                "action-" + UUID.randomUUID(), run.runId(), thread.threadId(), run.turnId(),
                thread.userId(), type, idempotencyKey, payload.toString(), ExternalActionStatusEnum.PENDING,
                0, 3, now, null, null, null, null, now, now, null
        );
        ExternalActionCommandModel existing = commands.createIfAbsent(command);
        workflowRuns.update(run.status(AgentWorkflowStatusEnum.WAITING_EXTERNAL_ACTION,
                steps("EXTERNAL_ACTION"), nextStateJson, now));
        appendAnswerResult(answerTurn, "APPROVED", run.runId(), now);
        projectOwner(thread, run.runId(), AgentTurnStatusEnum.WAITING_EXTERNAL_ACTION,
                null, "已授权，正在执行订单动作。", now);
        return new ResumeResult(message, "APPROVED", existing, null);
    }

    private QuestionPlan nextQuestion(
            WorkflowRequest request,
            List<OrderSnapshotModel> candidates,
            SelectedOrder selected
    ) {
        if (request.intent().isBlank()) {
            return intentQuestion();
        }
        if (ORDER_HISTORY.equals(request.intent())) {
            return historyActionQuestion();
        }
        if (!List.of(REFUND, EXPEDITE, HIDE_ORDER, RESTORE_ORDER).contains(request.intent())) {
            throw new AgentThreadConflictException("WORKFLOW_INTENT_INVALID", "售后操作类型无效");
        }
        if (selected == null) {
            if (candidates == null || candidates.isEmpty()) {
                throw new AgentThreadConflictException("ORDER_NOT_FOUND", "没有找到符合条件的订单");
            }
            return orderQuestion(candidates);
        }
        requireActionAllowed(request.intent(), selected.order());
        if (REFUND.equals(request.intent()) && request.reason().isBlank()) {
            return reasonQuestion(selected);
        }
        return confirmationQuestion(request, selected);
    }

    private QuestionPlan intentQuestion() {
        List<String> options = List.of(REFUND, EXPEDITE, ORDER_HISTORY);
        return new QuestionPlan(
                STEP_INTENT, "INTENT", "先确定售后事项",
                "你希望我处理哪一类订单售后？选择后我会继续查找相关订单。",
                List.of(field("intent", "售后事项", "SINGLE_SELECT", true, 32, options, false)),
                List.of(summary("处理范围", "退款、催发货或订单记录管理")));
    }

    private QuestionPlan historyActionQuestion() {
        return new QuestionPlan(
                STEP_HISTORY_ACTION, "HISTORY_ACTION", "选择订单记录操作",
                "订单记录只影响你的历史列表，不会删除交易或物流审计事实。请选择要执行的操作。",
                List.of(field("historyAction", "记录操作", "SINGLE_SELECT", true, 32,
                        List.of(HIDE_ORDER, RESTORE_ORDER), false)),
                List.of(summary("操作范围", "隐藏或恢复订单历史记录")));
    }

    private QuestionPlan orderQuestion(List<OrderSnapshotModel> candidates) {
        List<String> options = candidates.stream().limit(3).map(OrderSnapshotModel::orderId).toList();
        String prompt = "我找到多笔可能相关的订单。请选择要处理的订单；如果列表中没有，请选择“其他”并填写订单号。\n\n"
                + candidates.stream().limit(3).map(this::orderSummary).reduce((left, right) -> left + "\n" + right).orElse("");
        return new QuestionPlan(
                STEP_ORDER, "ORDER_SELECT", "请确认具体订单", prompt,
                List.of(field("orderId", "订单号", "SINGLE_SELECT", true, 64, options, true)),
                candidates.stream().limit(3).map(order -> summary("候选订单", orderSummary(order))).toList());
    }

    private QuestionPlan reasonQuestion(SelectedOrder selected) {
        OrderSnapshotModel order = selected.order();
        return new QuestionPlan(
                STEP_REASON, "REASON", "补充退款原因",
                "为了让售后记录完整，请告诉我这笔退款的原因。提交后还会让你在最终确认卡中授权。",
                List.of(field("reason", "退款原因", "TEXT", true, MAX_REASON_LENGTH, List.of(), true)),
                List.of(summary("订单", orderSummary(order)),
                        summary("物流", logisticsSummary(order, selected.events()))));
    }

    private QuestionPlan confirmationQuestion(WorkflowRequest request, SelectedOrder selected) {
        String action = switch (request.intent()) {
            case REFUND -> "退款";
            case EXPEDITE -> "催发货";
            case HIDE_ORDER -> "隐藏订单记录";
            case RESTORE_ORDER -> "恢复订单记录";
            default -> "订单操作";
        };
        String consequence = switch (request.intent()) {
            case REFUND -> "确认后会提交退款动作；相同 Workflow 重试只会产生一次退款。";
            case EXPEDITE -> "确认后会记录催发货请求，不会删除订单或物流记录。";
            case HIDE_ORDER -> "确认后只会从当前订单历史中隐藏，不会删除交易或物流审计事实。";
            case RESTORE_ORDER -> "确认后会把订单恢复到当前订单历史列表。";
            default -> "确认后才会提交订单动作。";
        };
        List<Map<String, String>> summaries = new ArrayList<>(List.of(
                summary("操作", action),
                summary("订单", orderSummary(selected.order())),
                summary("物流", logisticsSummary(selected.order(), selected.events()))));
        if (REFUND.equals(request.intent())) {
            summaries.add(summary("退款原因", request.reason()));
        }
        return new QuestionPlan(
                STEP_CONFIRM, "CONFIRM", "请确认这项订单操作",
                "请核对订单和物流信息。" + consequence,
                List.of(field("decision", "是否提交" + action, "SINGLE_SELECT", true, 32,
                        List.of("APPROVE", "REJECT"), false)),
                summaries);
    }

    private AgentWorkflowQuestionModel question(
            String runId,
            AgentThreadModel thread,
            AgentTurnModel sourceTurn,
            QuestionPlan plan,
            Instant now
    ) {
        if (sourceTurn == null) {
            throw new IllegalStateException("Workflow Question 必须绑定 owner Turn");
        }
        String questionId = "question-" + UUID.randomUUID();
        String checkpointId = "order-service-" + plan.stepName().toLowerCase(Locale.ROOT) + "-" + UUID.randomUUID();
        String fieldsJson = writeJson(Map.of(
                "operation", ORDER_SERVICE,
                "step", plan.stepName(),
                "stepNo", plan.stepNo(),
                "summary", plan.summary(),
                "fields", plan.fields()
        ));
        return new AgentWorkflowQuestionModel(
                runId, thread.threadId(), sourceTurn.turnId(), thread.userId(), questionId,
                checkpointId, plan.stepNo(), 0L, plan.title(), bounded(plan.prompt(), 1_900), fieldsJson,
                AgentWorkflowQuestionStatusEnum.OPEN, now, null, null,
                AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum.AVAILABLE, plan.answerFields());
    }

    private SelectedOrder selectedOrder(String userId, OrderSnapshotModel snapshot) {
        List<LogisticsEventModel> trace = List.of();
        if (logistics != null) {
            try {
                List<LogisticsEventModel> result = logistics.findTrace(snapshot.orderId(), userId);
                trace = result == null ? List.of() : result.stream().filter(value -> value != null).limit(20).toList();
            } catch (RuntimeException failure) {
                throw new AgentThreadConflictException(
                        "LOGISTICS_TEMPORARY_FAILURE", "物流信息暂时无法核验，请稍后重试");
            }
        }
        return new SelectedOrder(snapshot, trace);
    }

    private OrderSnapshotModel lookupSelected(String orderId, String userId) {
        if (orders == null) {
            throw new AgentThreadConflictException("ORDER_GATEWAY_UNAVAILABLE", "订单服务暂时不可用");
        }
        OrderLookupResultModel lookup = orders.findOrder(orderId, userId);
        if (lookup == null || lookup.status() == OrderLookupStatusEnum.TEMPORARY_FAILURE) {
            throw new AgentThreadConflictException("ORDER_TEMPORARY_FAILURE", "订单信息暂时无法核验，请稍后重试");
        }
        if (lookup.status() == OrderLookupStatusEnum.ACCESS_DENIED) {
            throw new AgentThreadConflictException("ORDER_NOT_OWNED", "订单不属于当前用户");
        }
        if (lookup.status() != OrderLookupStatusEnum.FOUND || lookup.order() == null) {
            throw new AgentThreadConflictException("ORDER_NOT_FOUND", "订单不存在或已不可见");
        }
        return lookup.order();
    }

    private ResolvedCandidates resolveCandidates(WorkflowRequest request, String userId) {
        if (orders == null) {
            throw new AgentThreadConflictException("ORDER_GATEWAY_UNAVAILABLE", "订单服务暂时不可用");
        }
        if (!request.orderId().isBlank()) {
            return new ResolvedCandidates(List.of(lookupSelected(request.orderId(), userId)), true);
        }
        OrderSearchResultModel result = orders.searchOrders(request.criteria(), userId);
        if (result == null || result.status() == OrderSearchStatusEnum.TEMPORARY_FAILURE) {
            throw new AgentThreadConflictException("ORDER_TEMPORARY_FAILURE", "订单搜索暂时不可用，请稍后重试");
        }
        return new ResolvedCandidates(result.orders().stream().filter(value -> value != null).limit(20).toList(), false);
    }

    private OrderSnapshotModel selectCandidate(WorkflowRequest request, ResolvedCandidates candidates) {
        if (!request.orderId().isBlank()) {
            return candidates.orders().stream()
                    .filter(order -> request.orderId().equals(order.orderId())).findFirst().orElse(null);
        }
        return candidates.orders().size() == 1 ? candidates.orders().get(0) : null;
    }

    private void requireActionAllowed(String intent, OrderSnapshotModel order) {
        if (order == null) {
            throw new AgentThreadConflictException("ORDER_REQUIRED", "请先选择具体订单");
        }
        if (REFUND.equals(intent)
                && !List.of(OrderStatusEnum.PAID, OrderStatusEnum.SHIPPED,
                OrderStatusEnum.DELIVERED, OrderStatusEnum.REFUNDED).contains(order.status())) {
            throw new AgentThreadConflictException("REFUND_ORDER_STATE_INVALID", "当前订单状态不允许退款");
        }
        if (EXPEDITE.equals(intent) && order.status() != OrderStatusEnum.PAID) {
            throw new AgentThreadConflictException("EXPEDITE_ORDER_STATE_INVALID", "仅已支付订单允许催发货");
        }
    }

    private ExternalActionTypeEnum actionType(String intent) {
        return switch (normalizeIntent(intent)) {
            case REFUND -> ExternalActionTypeEnum.REFUND;
            case EXPEDITE -> ExternalActionTypeEnum.EXPEDITE;
            case HIDE_ORDER -> ExternalActionTypeEnum.HIDE_ORDER;
            case RESTORE_ORDER -> ExternalActionTypeEnum.RESTORE_ORDER;
            default -> throw new AgentThreadConflictException("WORKFLOW_INTENT_INVALID", "售后操作类型无效");
        };
    }

    private void appendOrderFacts(
            AgentThreadModel thread,
            AgentTurnModel turn,
            List<OrderSnapshotModel> candidates,
            SelectedOrder selected,
            Instant now
    ) {
        if (turn == null) {
            return;
        }
        if (candidates != null && !candidates.isEmpty()) {
            appendItem(thread, turn, AgentItemTypeEnum.ORDER_LIST, writeJson(Map.of(
                    "status", "SUCCESS",
                    "orders", candidates.stream().limit(20).map(this::safeOrder).toList())), now);
        }
        if (selected != null) {
            appendItem(thread, turn, AgentItemTypeEnum.ORDER_DETAIL, writeJson(safeOrder(selected.order())), now);
            appendItem(thread, turn, AgentItemTypeEnum.LOGISTICS_TIMELINE, writeJson(Map.of(
                    "orderId", selected.order().orderId(),
                    "events", selected.events().stream().map(this::safeLogistics).toList())), now);
        }
    }

    private void appendOwnerQuestion(
            AgentThreadModel thread,
            AgentWorkflowRunModel run,
            AgentWorkflowQuestionModel question,
            Instant now
    ) {
        AgentTurnModel owner = threadOwnerTurn(thread, run);
        if (owner != null) {
            appendItem(thread, owner, AgentItemTypeEnum.WORKFLOW_QUESTION, questionPayload(question), now);
        }
    }

    private AgentTurnModel threadOwnerTurn(AgentThreadModel thread, AgentWorkflowRunModel run) {
        if (turns == null) {
            return null;
        }
        return turns.findTurn(thread.userId(), run.turnId()).orElse(null);
    }

    private void projectOwner(
            AgentThreadModel thread,
            String runId,
            AgentTurnStatusEnum target,
            String errorCode,
            String resultMessage,
            Instant now
    ) {
        if (turns == null) {
            return;
        }
        AgentWorkflowRunModel run = workflowRuns.find(thread.userId(), runId)
                .orElseThrow(() -> new IllegalStateException("Workflow owner 对应 Run 不存在：" + runId));
        AgentTurnModel owner = turns.findTurn(thread.userId(), run.turnId()).orElse(null);
        if (owner == null || isTerminal(owner.status())) {
            return;
        }
        AgentTurnModel next = switch (target) {
            case WAITING_USER_INPUT, WAITING_EXTERNAL_ACTION -> owner.workflow(runId, target);
            case COMPLETED, FAILED -> owner.terminal(target, errorCode, now);
            default -> throw new IllegalArgumentException("不能将 Workflow 投影为 owner Turn 状态：" + target);
        };
        if (!turns.updateTurn(owner, next)) {
            throw new AgentThreadConflictException("TURN_VERSION_CONFLICT", "Workflow owner Turn 版本竞争：" + owner.turnId());
        }
        if (resultMessage != null && !resultMessage.isBlank()) {
            appendItem(thread, next, AgentItemTypeEnum.WORKFLOW_RESULT,
                    writeJson(Map.of("runId", runId, "status", target.name(), "message", resultMessage)), now);
        }
        appendItem(thread, next, AgentItemTypeEnum.TURN_STATE,
                writeJson(Map.of("status", target.name())), now);
    }

    private void appendAnswerResult(AgentTurnModel answerTurn, String status, String runId, Instant now) {
        if (items == null) {
            return;
        }
        appendItem(new AgentItemModel(UUID.randomUUID().toString(), answerTurn.threadId(), answerTurn.turnId(), 0,
                AgentItemTypeEnum.WORKFLOW_RESULT,
                writeJson(Map.of("runId", runId, "status", status)), now));
    }

    private AgentItemModel appendItem(
            AgentThreadModel thread,
            AgentTurnModel turn,
            AgentItemTypeEnum type,
            String payload,
            Instant createdAt
    ) {
        return appendItem(new AgentItemModel(UUID.randomUUID().toString(), thread.threadId(), turn.turnId(), 0,
                type, payload, createdAt));
    }

    private AgentItemModel appendItem(AgentItemModel draft) {
        if (items == null) {
            return draft;
        }
        long sequence = items.appendItem(draft);
        AgentItemModel persisted = new AgentItemModel(draft.itemId(), draft.threadId(), draft.turnId(), sequence,
                draft.type(), draft.payload(), draft.createdAt());
        emitAfterCommit(persisted);
        return persisted;
    }

    private void emitAfterCommit(AgentItemModel item) {
        if (events == null) {
            return;
        }
        afterCommit(() -> events.itemCreated(item));
    }

    private void afterCommit(Runnable callback) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    callback.run();
                }
            });
        } else {
            callback.run();
        }
    }

    private String questionPayload(AgentWorkflowQuestionModel question) {
        try {
            JsonNode schema = objectMapper.readTree(question.fieldsJson());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("runId", question.runId());
            payload.put("questionId", question.questionId());
            payload.put("checkpointId", question.checkpointId());
            payload.put("stepNo", question.stepNo());
            payload.put("version", question.version());
            payload.put("title", question.title());
            payload.put("prompt", question.prompt());
            if (schema.has("operation")) {
                payload.put("operation", schema.path("operation"));
            }
            if (schema.has("step")) {
                payload.put("step", schema.path("step"));
            }
            if (schema.has("summary")) {
                payload.put("summary", schema.path("summary"));
            }
            payload.put("fields", schema.path("fields"));
            return writeJson(payload);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("无法生成 QuestionCard Item", failure);
        }
    }

    private Map<String, Object> state(
            WorkflowRequest request,
            List<OrderSnapshotModel> candidates,
            SelectedOrder selected,
            String reason
    ) {
        Map<String, Object> state = new LinkedHashMap<>();
        put(state, "intent", request.intent());
        put(state, "orderId", selected == null ? request.orderId() : selected.order().orderId());
        put(state, "reason", reason == null || reason.isBlank() ? request.reason() : reason);
        if (HIDE_ORDER.equals(request.intent()) || RESTORE_ORDER.equals(request.intent())) {
            state.put("visibility", HIDE_ORDER.equals(request.intent()) ? "HIDDEN" : "ACTIVE");
        }
        state.put("criteria", request.criteriaValues());
        state.put("candidateOrderIds", candidates == null ? List.of()
                : candidates.stream().limit(20).map(OrderSnapshotModel::orderId).toList());
        if (selected != null) {
            state.put("selectedOrder", safeOrder(selected.order()));
            state.put("logistics", selected.events().stream().map(this::safeLogistics).toList());
        }
        return state;
    }

    private WorkflowRequest requestFromState(AgentWorkflowRunModel run) {
        try {
            JsonNode root = objectMapper.readTree(run.stateJson());
            Map<String, String> criteria = new LinkedHashMap<>();
            JsonNode criteriaNode = root.path("criteria");
            if (criteriaNode.isObject()) {
                criteriaNode.properties().forEach(entry -> criteria.put(entry.getKey(), text(entry.getValue())));
            }
            return new WorkflowRequest(
                    normalizeIntent(text(root.path("intent"))),
                    text(root.path("orderId")),
                    text(root.path("reason")), criteria);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("无法恢复订单 Workflow 状态", failure);
        }
    }

    private String requiredState(AgentWorkflowRunModel run, String field) {
        String value = requestFromState(run).value(field);
        if (value.isBlank()) {
            throw new AgentThreadConflictException("WORKFLOW_STATE_INCOMPLETE", "Workflow 缺少必要状态：" + field);
        }
        return value;
    }

    private String questionStep(AgentWorkflowQuestionModel question) {
        try {
            JsonNode root = objectMapper.readTree(question.fieldsJson());
            String step = text(root.path("step")).trim().toUpperCase(Locale.ROOT);
            return step.isBlank() ? "LEGACY" : step;
        } catch (RuntimeException failure) {
            throw new IllegalStateException("无法解析 Workflow Question 步骤", failure);
        }
    }

    private boolean isLegacyQuestion(AgentWorkflowQuestionModel question, AgentWorkflowRunModel run) {
        return run.workflowType() != AgentWorkflowTypeEnum.ORDER_SERVICE
                || "LEGACY".equals(questionStep(question));
    }

    private boolean legacyTypeMatches(AgentWorkflowTypeEnum type, String intent) {
        return (type == AgentWorkflowTypeEnum.REFUND && REFUND.equals(intent))
                || (type == AgentWorkflowTypeEnum.EXPEDITE && EXPEDITE.equals(intent));
    }

    private String orderSummary(OrderSnapshotModel order) {
        return order.orderId() + " · " + (order.itemSummary() == null ? "商品信息未知" : order.itemSummary())
                + " · " + order.status().name()
                + (order.paidAmount() == null ? "" : " · " + order.paidAmount() + " " + safe(order.currency()));
    }

    private String logisticsSummary(OrderSnapshotModel order, List<LogisticsEventModel> trace) {
        if (order.logisticsStatus() != null && !order.logisticsStatus().isBlank()) {
            return order.logisticsStatus() + (trace == null || trace.isEmpty() ? "" : "，" + trace.size() + " 个节点");
        }
        return trace == null || trace.isEmpty() ? "暂无物流节点" : trace.size() + " 个物流节点";
    }

    private Map<String, Object> safeOrder(OrderSnapshotModel order) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("orderId", order.orderId());
        value.put("status", order.status().name());
        put(value, "createdAt", instant(order.createdAt()));
        put(value, "expectedDeliveryAt", instant(order.expectedDeliveryAt()));
        put(value, "lastLogisticsAt", instant(order.lastLogisticsAt()));
        put(value, "logisticsStatus", order.logisticsStatus());
        put(value, "paidAmount", order.paidAmount() == null ? null : order.paidAmount().toPlainString());
        put(value, "currency", order.currency());
        put(value, "itemSummary", order.itemSummary());
        value.put("visibility", order.hiddenAt() == null ? "ACTIVE" : "HIDDEN");
        return value;
    }

    private Map<String, Object> safeLogistics(LogisticsEventModel event) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("eventId", event.eventId());
        value.put("status", event.status());
        value.put("location", event.location());
        value.put("description", event.description());
        value.put("occurredAt", event.occurredAt().toString());
        return value;
    }

    private String steps(String activeStep) {
        List<Map<String, String>> result = new ArrayList<>();
        result.add(step("PARSE_CONDITIONS", "COMPLETED"));
        result.add(step("CANDIDATE_ORDERS", List.of("ORDER_SELECT", "INTENT", "HISTORY_ACTION").contains(activeStep)
                ? "WAITING" : "COMPLETED"));
        result.add(step("ORDER_LOGISTICS_VERIFICATION", List.of("REASON", "CONFIRM", "EXTERNAL_ACTION", "TERMINAL")
                .contains(activeStep) ? "COMPLETED" : "PENDING"));
        result.add(step("USER_INPUT", List.of("INTENT", "ORDER_SELECT", "REASON", "HISTORY_ACTION").contains(activeStep)
                ? "WAITING" : "COMPLETED"));
        result.add(step("FINAL_AUTHORIZATION", "CONFIRM".equals(activeStep)
                ? "WAITING" : List.of("EXTERNAL_ACTION", "TERMINAL").contains(activeStep) ? "COMPLETED" : "PENDING"));
        result.add(step("EXTERNAL_ACTION", "EXTERNAL_ACTION".equals(activeStep)
                ? "ACTIVE" : "TERMINAL".equals(activeStep) ? "COMPLETED" : "PENDING"));
        result.add(step("TERMINAL", "TERMINAL".equals(activeStep) ? "COMPLETED" : "PENDING"));
        return writeJson(result);
    }

    private Map<String, String> step(String name, String status) {
        return Map.of("name", name, "status", status);
    }

    private Map<String, Object> field(
            String name,
            String label,
            String type,
            boolean required,
            int maxLength,
            List<String> options,
            boolean allowCustom
    ) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("name", name);
        value.put("label", label);
        value.put("type", type);
        value.put("required", required);
        value.put("maxLength", maxLength);
        value.put("options", options);
        value.put("allowCustom", allowCustom);
        return value;
    }

    private Map<String, String> summary(String label, String value) {
        return value == null || value.isBlank() ? null : Map.of("label", label, "value", bounded(value, 256));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception failure) {
            throw new IllegalStateException("无法编码订单 Workflow 状态", failure);
        }
    }

    private String requiredAnswer(Map<String, String> answers, String field) {
        String value = answers.getOrDefault(field, "").trim();
        if (value.isBlank()) {
            throw new AgentThreadConflictException("WORKFLOW_ANSWER_INVALID", "缺少回答字段：" + field);
        }
        return value;
    }

    private AgentWorkflowDecisionEnum parseDecision(String value) {
        if (value == null) {
            return AgentWorkflowDecisionEnum.REJECT;
        }
        String normalized = value.trim();
        return normalized.equalsIgnoreCase("APPROVE")
                || normalized.equalsIgnoreCase("CONFIRM") || normalized.equals("同意")
                ? AgentWorkflowDecisionEnum.APPROVE : AgentWorkflowDecisionEnum.REJECT;
    }

    private static String normalizeIntent(String value) {
        if (value == null) {
            return "";
        }
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "REFUND", "退款" -> REFUND;
            case "EXPEDITE", "催发货", "催单" -> EXPEDITE;
            case "ORDER_HISTORY", "订单记录", "订单历史", "历史" -> ORDER_HISTORY;
            case "HIDE_ORDER", "HIDE", "隐藏订单", "隐藏" -> HIDE_ORDER;
            case "RESTORE_ORDER", "RESTORE", "恢复订单", "恢复" -> RESTORE_ORDER;
            default -> value.trim().isBlank() ? "" : value.trim().toUpperCase(Locale.ROOT);
        };
    }

    private String bounded(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    private String instant(Instant value) {
        return value == null ? null : value.toString();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private static String text(JsonNode value) {
        if (value == null || value.isNull()) {
            return "";
        }
        return value.asString("");
    }

    private void put(Map<String, Object> target, String key, Object value) {
        if (value != null && (!(value instanceof String string) || !string.isBlank())) {
            target.put(key, value);
        }
    }

    private boolean isTerminal(AgentTurnStatusEnum status) {
        return status == AgentTurnStatusEnum.COMPLETED
                || status == AgentTurnStatusEnum.FAILED
                || status == AgentTurnStatusEnum.CANCELLED
                || status == AgentTurnStatusEnum.TIMED_OUT;
    }

    private <T> T inTransaction(Supplier<T> work) {
        if (transactionTemplate == null) {
            return work.get();
        }
        T result = transactionTemplate.execute(status -> work.get());
        if (result == null) {
            throw new IllegalStateException("Workflow 事务未返回结果");
        }
        return result;
    }

    private static String value(Map<String, String> arguments, String key) {
        if (arguments == null) {
            return "";
        }
        String value = arguments.get(key);
        return value == null ? "" : value.trim();
    }

    private static String parseIntent(String operation, Map<String, String> arguments) {
        String explicit = value(arguments, "intent");
        if (!explicit.isBlank()) {
            return explicit;
        }
        String normalized = operation == null ? "" : operation.trim();
        String intent = normalizeIntent(normalized);
        return List.of(REFUND, EXPEDITE, ORDER_HISTORY, HIDE_ORDER, RESTORE_ORDER).contains(intent)
                ? intent : "";
    }

    private static Instant parseBoundary(String name, String value, boolean endOfDay) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException instantFailure) {
            try {
                return LocalDate.parse(value.trim()).atTime(endOfDay ? LocalTime.MAX : LocalTime.MIN)
                        .toInstant(ZoneOffset.UTC);
            } catch (DateTimeParseException dateFailure) {
                throw new IllegalArgumentException("订单 Workflow 日期参数无效：" + name);
            }
        }
    }

    private static BigDecimal parseAmount(String name, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("订单 Workflow 金额参数无效：" + name);
        }
    }

    private static Set<OrderStatusEnum> parseStatuses(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .map(item -> OrderStatusEnum.valueOf(item.toUpperCase(Locale.ROOT)))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static OrderVisibilityEnum parseVisibility(String value) {
        return value == null || value.isBlank()
                ? OrderVisibilityEnum.ACTIVE
                : OrderVisibilityEnum.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    private record QuestionPlan(
            int stepNo,
            String stepName,
            String title,
            String prompt,
            List<Map<String, Object>> fields,
            List<Map<String, String>> summary
    ) {
        private QuestionPlan {
            fields = List.copyOf(fields);
            summary = summary == null ? List.of() : summary.stream().filter(value -> value != null).toList();
        }

        private List<AgentWorkflowQuestionFieldModel> answerFields() {
            return fields.stream().map(field -> {
                @SuppressWarnings("unchecked")
                List<String> options = (List<String>) field.getOrDefault("options", List.of());
                return new AgentWorkflowQuestionFieldModel(
                        (String) field.get("name"),
                        Boolean.TRUE.equals(field.get("required")),
                        ((Number) field.getOrDefault("maxLength", 256)).intValue(),
                        options,
                        Boolean.TRUE.equals(field.get("allowCustom")));
            }).toList();
        }
    }

    private record ResolvedCandidates(List<OrderSnapshotModel> orders, boolean exact) {
        private ResolvedCandidates {
            orders = orders == null ? List.of() : List.copyOf(orders);
        }

        private static ResolvedCandidates empty() {
            return new ResolvedCandidates(List.of(), false);
        }
    }

    private record SelectedOrder(OrderSnapshotModel order, List<LogisticsEventModel> events) {
        private SelectedOrder {
            events = events == null ? List.of() : List.copyOf(events);
        }
    }

    private record CandidatePreparation(
            WorkflowRequest request,
            ResolvedCandidates candidates,
            SelectedOrder selected
    ) {
    }

    private record ResumePreparation(
            AgentWorkflowQuestionModel question,
            AgentWorkflowRunModel run,
            Map<String, String> answers,
            String step,
            CandidatePreparation candidate
    ) {
        private ResumePreparation {
            answers = Map.copyOf(answers);
        }
    }

    private record WorkflowRequest(
            String intent,
            String orderId,
            String reason,
            Map<String, String> criteriaValues
    ) {
        private WorkflowRequest {
            intent = TransactionalAgentWorkflowEngine.normalizeIntent(intent);
            orderId = orderId == null ? "" : orderId.trim();
            reason = reason == null ? "" : reason.trim();
            criteriaValues = criteriaValues == null ? Map.of() : Map.copyOf(criteriaValues);
        }

        private static WorkflowRequest from(String operation, Map<String, String> arguments) {
            Map<String, String> criteria = new LinkedHashMap<>();
            for (String key : List.of("createdFrom", "createdTo", "minAmount", "maxAmount", "statuses",
                    "keyword", "logisticsStalledDays", "visibility")) {
                String value = TransactionalAgentWorkflowEngine.value(arguments, key);
                if (!value.isBlank()) {
                    criteria.put(key, value);
                }
            }
            return new WorkflowRequest(parseIntent(operation, arguments),
                    TransactionalAgentWorkflowEngine.value(arguments, "orderId"),
                    TransactionalAgentWorkflowEngine.value(arguments, "reason"), criteria);
        }

        private OrderSearchCriteria criteria() {
            String createdFrom = criteriaValues.getOrDefault("createdFrom", "");
            String createdTo = criteriaValues.getOrDefault("createdTo", "");
            String minAmount = criteriaValues.getOrDefault("minAmount", "");
            String maxAmount = criteriaValues.getOrDefault("maxAmount", "");
            String statuses = criteriaValues.getOrDefault("statuses", "");
            String keyword = criteriaValues.getOrDefault("keyword", "");
            String stalled = criteriaValues.getOrDefault("logisticsStalledDays", "");
            int stalledDays = stalled.isBlank() ? 0 : Integer.parseInt(stalled);
            return new OrderSearchCriteria(
                    parseBoundary("createdFrom", createdFrom, false),
                    parseBoundary("createdTo", createdTo, true),
                    parseAmount("minAmount", minAmount), parseAmount("maxAmount", maxAmount),
                    parseStatuses(statuses), keyword, stalled.isBlank() ? null : stalledDays,
                    parseVisibility(criteriaValues.getOrDefault("visibility",
                            RESTORE_ORDER.equals(intent) ? "HIDDEN" : "ACTIVE")), 20);
        }

        private WorkflowRequest withIntent(String value) {
            return new WorkflowRequest(value, orderId, reason, criteriaValues);
        }

        private WorkflowRequest withOrderId(String value) {
            return new WorkflowRequest(intent, value, reason, criteriaValues);
        }

        private WorkflowRequest withReason(String value) {
            return new WorkflowRequest(intent, orderId, value, criteriaValues);
        }

        private String value(String key) {
            return switch (key) {
                case "intent" -> intent;
                case "orderId" -> orderId;
                case "reason" -> reason;
                default -> criteriaValues.getOrDefault(key, "");
            };
        }
    }
}
