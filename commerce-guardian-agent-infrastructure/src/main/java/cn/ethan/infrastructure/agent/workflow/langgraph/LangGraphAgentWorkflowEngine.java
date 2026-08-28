package cn.ethan.infrastructure.agent.workflow.langgraph;

import cn.ethan.core.agent.action.ExternalActionCommandModel;
import cn.ethan.core.agent.action.ExternalActionCommandStore;
import cn.ethan.core.agent.action.ExternalActionStatusEnum;
import cn.ethan.core.agent.action.ExternalActionTypeEnum;
import cn.ethan.core.agent.event.AgentThreadEventGateway;
import cn.ethan.core.agent.execution.AgentTurnItemPayloads;
import cn.ethan.core.agent.thread.AgentItemModel;
import cn.ethan.core.agent.thread.AgentItemStore;
import cn.ethan.core.agent.thread.AgentItemTypeEnum;
import cn.ethan.core.agent.thread.AgentQuestionAnswerInput;
import cn.ethan.core.agent.thread.AgentThreadConflictException;
import cn.ethan.core.agent.thread.AgentThreadModel;
import cn.ethan.core.agent.thread.AgentTurnModel;
import cn.ethan.core.agent.thread.AgentTurnStatusEnum;
import cn.ethan.core.agent.thread.AgentTurnStore;
import cn.ethan.core.agent.thread.AgentWorkflowDecisionInput;
import cn.ethan.core.agent.workflow.AgentQuestionCardAnswerActionEnum;
import cn.ethan.core.agent.workflow.AgentQuestionCardModel;
import cn.ethan.core.agent.workflow.AgentQuestionCardStore;
import cn.ethan.core.agent.workflow.AgentWorkflowCheckpointModel;
import cn.ethan.core.agent.workflow.AgentWorkflowCheckpointStatusEnum;
import cn.ethan.core.agent.workflow.AgentWorkflowCheckpointStore;
import cn.ethan.core.agent.workflow.AgentWorkflowDecisionEnum;
import cn.ethan.core.agent.workflow.AgentWorkflowEngine;
import cn.ethan.core.agent.workflow.AgentWorkflowRunModel;
import cn.ethan.core.agent.workflow.AgentWorkflowRunStore;
import cn.ethan.core.agent.workflow.AgentWorkflowStatusEnum;
import cn.ethan.core.agent.workflow.AgentWorkflowTypeEnum;
import cn.ethan.core.agent.workflow.AgentQuestionFieldModel;
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
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.bsc.langgraph4j.state.AgentState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
 * 类型职责：使用 LangGraph4j 驱动固定订单 Workflow，并把业务授权留在独立 Checkpoint。
 *
 * <p>图只负责节点顺序、恢复和技术快照；订单、WorkflowRun、QuestionCard、Checkpoint
 * 和 ExternalActionCommand 仍是项目的业务事实源。远程订单/物流读取发生在本地写事务之外，
 * AUTHORIZE 节点只创建待确认 Checkpoint，EXECUTE_ACTION 只创建可靠外部动作命令。</p>
 *
 * @author ethan
 * @date 2026-08-27
 */
@Component
public final class LangGraphAgentWorkflowEngine implements AgentWorkflowEngine {

    private static final String ORDER_SERVICE = "ORDER_SERVICE";
    private static final String REFUND = "REFUND";
    private static final String EXPEDITE = "EXPEDITE";
    private static final String HIDE_ORDER = "HIDE_ORDER";
    private static final String RESTORE_ORDER = "RESTORE_ORDER";
    private static final int MAX_REASON_LENGTH = 512;
    private static final int MAX_CANDIDATES = 20;
    private static final int MAX_LOGISTICS = 20;

    private final Clock clock;
    private final ExternalActionCommandStore commands;
    private final ObjectMapper objectMapper;
    private final AgentWorkflowRunStore workflowRuns;
    private final OrderGateway orders;
    private final LogisticsGateway logistics;
    private final AgentItemStore items;
    private final AgentTurnStore turns;
    private final AgentThreadEventGateway events;
    private final AgentQuestionCardStore questionCards;
    private final AgentWorkflowCheckpointStore checkpoints;
    private final TransactionTemplate transactionTemplate;
    private final CompiledGraph<AgentState> graph;

    /** 生产装配边界：只由该 Bean 持有 LangGraph 固定订单图。 */
    @Autowired
    public LangGraphAgentWorkflowEngine(
            Clock clock,
            ExternalActionCommandStore commands,
            ObjectMapper objectMapper,
            AgentWorkflowRunStore workflowRuns,
            OrderGateway orders,
            LogisticsGateway logistics,
            AgentItemStore items,
            AgentTurnStore turns,
            AgentThreadEventGateway events,
            AgentQuestionCardStore questionCards,
            AgentWorkflowCheckpointStore checkpoints,
            PlatformTransactionManager transactionManager,
            MybatisLangGraphCheckpointSaver saver,
            @Value("${ai-agent.workflow.graph-recursion-limit:32}") int recursionLimit
    ) {
        this(clock, commands, objectMapper, workflowRuns, orders, logistics, items, turns, events,
                questionCards, checkpoints, transactionManager, saver, recursionLimit, true);
    }

    /** 测试边界：允许不提供数据库事务和 MyBatis Saver，使用内存技术快照。 */
    public LangGraphAgentWorkflowEngine(
            Clock clock,
            ExternalActionCommandStore commands,
            ObjectMapper objectMapper,
            AgentWorkflowRunStore workflowRuns,
            OrderGateway orders,
            LogisticsGateway logistics,
            AgentItemStore items,
            AgentTurnStore turns,
            AgentThreadEventGateway events,
            AgentQuestionCardStore questionCards,
            AgentWorkflowCheckpointStore checkpoints
    ) {
        this(clock, commands, objectMapper, workflowRuns, orders, logistics, items, turns, events,
                questionCards, checkpoints, null, null, 32, false);
    }

    /** 测试边界：注入技术快照 Saver，验证业务 Run 与图快照的持久化顺序。 */
    LangGraphAgentWorkflowEngine(
            Clock clock,
            ExternalActionCommandStore commands,
            ObjectMapper objectMapper,
            AgentWorkflowRunStore workflowRuns,
            OrderGateway orders,
            LogisticsGateway logistics,
            AgentItemStore items,
            AgentTurnStore turns,
            AgentThreadEventGateway events,
            AgentQuestionCardStore questionCards,
            AgentWorkflowCheckpointStore checkpoints,
            BaseCheckpointSaver saver
    ) {
        this(clock, commands, objectMapper, workflowRuns, orders, logistics, items, turns, events,
                questionCards, checkpoints, null, saver, 32, false);
    }

    private LangGraphAgentWorkflowEngine(
            Clock clock,
            ExternalActionCommandStore commands,
            ObjectMapper objectMapper,
            AgentWorkflowRunStore workflowRuns,
            OrderGateway orders,
            LogisticsGateway logistics,
            AgentItemStore items,
            AgentTurnStore turns,
            AgentThreadEventGateway events,
            AgentQuestionCardStore questionCards,
            AgentWorkflowCheckpointStore checkpoints,
            PlatformTransactionManager transactionManager,
            BaseCheckpointSaver saver,
            int recursionLimit,
            boolean production
    ) {
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.commands = commands;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.workflowRuns = workflowRuns;
        this.orders = orders;
        this.logistics = logistics;
        this.items = items;
        this.turns = turns;
        this.events = events;
        this.questionCards = questionCards;
        this.checkpoints = checkpoints;
        this.transactionTemplate = transactionManager == null ? null : new TransactionTemplate(transactionManager);
        BaseCheckpointSaver effectiveSaver = saver == null ? new MemorySaver() : saver;
        try {
            this.graph = new LangGraphWorkflowGraphFactory(this.objectMapper)
                    .createOrderWorkflow(effectiveSaver, Math.max(1, Math.min(recursionLimit, 256)));
        } catch (Exception failure) {
            throw new IllegalStateException("无法编译 LangGraph 订单 Workflow", failure);
        }
    }

    @Override
    public StartResult start(
            AgentThreadModel thread,
            AgentTurnModel turn,
            String operation,
            Map<String, String> arguments
    ) {
        requireDependencies();
        WorkflowRequest request = WorkflowRequest.from(operation, arguments);
        if (request.intent().isBlank()) {
            throw new AgentThreadConflictException("WORKFLOW_INTENT_REQUIRED", "订单 Workflow 缺少售后意图");
        }
        ResolvedCandidates candidates = resolveCandidates(request, thread.userId());
        SelectedOrder selected = selectCandidate(request, candidates, thread.userId());
        if (selected != null && !isReasonMissing(request)) {
            requireActionAllowed(request.intent(), selected.order());
        }

        String runId = "workflow-" + UUID.randomUUID();
        Instant now = clock.instant();
        String factsFingerprint = factsFingerprint(request, candidates, selected);
        Map<String, Object> businessState = state(request, candidates.orders(), selected, request.reason());
        Map<String, Object> graphState = new LinkedHashMap<>(businessState);
        graphState.put("factsDecision", "READY");
        graphState.put("workflowVersion", 0L);
        graphState.put("factsFingerprint", factsFingerprint);

        boolean missingOrder = selected == null;
        boolean missingReason = !missingOrder && isReasonMissing(request);
        String activeNode = missingOrder ? LangGraphWorkflowGraphFactory.RESOLVE_ORDER
                : missingReason ? LangGraphWorkflowGraphFactory.SWITCH_REQUIREMENTS
                : LangGraphWorkflowGraphFactory.AUTHORIZE;
        AgentWorkflowRunModel run = new AgentWorkflowRunModel(
                runId, thread.threadId(), turn.turnId(), thread.userId(), AgentWorkflowTypeEnum.ORDER_SERVICE,
                AgentWorkflowStatusEnum.WAITING_USER_INPUT, 0L,
                steps(activeNode, missingOrder || missingReason ? "WAITING" : "WAITING"),
                writeJson(withGraphState(businessState, null, factsFingerprint, 0L)), now, now);

        AgentQuestionCardModel question = missingOrder || missingReason
                ? questionCard(run, turn, request, candidates, selected, missingOrder, now)
                : null;
        AgentWorkflowCheckpointModel checkpoint = question == null
                ? checkpoint(run, turn, request, selected, factsFingerprint, now)
                : null;
        return inTransaction(() -> persistStart(
                thread, turn, run, candidates, selected, question, checkpoint, graphState, factsFingerprint, now));
    }

    private StartResult persistStart(
            AgentThreadModel thread,
            AgentTurnModel turn,
            AgentWorkflowRunModel run,
            ResolvedCandidates candidates,
            SelectedOrder selected,
            AgentQuestionCardModel question,
            AgentWorkflowCheckpointModel checkpoint,
            Map<String, Object> graphState,
            String factsFingerprint,
            Instant now
    ) {
        if (questionCards.findOpen(thread.userId(), thread.threadId()).isPresent()
                || checkpoints.findOpen(thread.userId(), thread.threadId()).isPresent()) {
            throw new AgentThreadConflictException("THREAD_WORKFLOW_ACTIVE", "当前 Thread 已有开放交互");
        }
        workflowRuns.create(run);
        // AGENT_GRAPH_SNAPSHOT.RUN_ID 受 WorkflowRun 外键约束，必须在首个技术快照之前创建业务 Run。
        runGraph(run.runId(), graphState, run.version(), factsFingerprint, false);
        appendItem(thread, turn, AgentItemTypeEnum.WORKFLOW_STARTED,
                writeJson(Map.of("runId", run.runId(), "workflowType", ORDER_SERVICE)), now);
        appendOrderFacts(thread, turn, candidates.orders(), selected, now);
        if (question != null) {
            questionCards.create(question);
            appendItem(thread, turn, AgentItemTypeEnum.QUESTION_CARD,
                    AgentTurnItemPayloads.questionCard(question), now);
        } else {
            checkpoints.create(checkpoint);
            appendItem(thread, turn, AgentItemTypeEnum.WORKFLOW_CHECKPOINT,
                    AgentTurnItemPayloads.workflowCheckpoint(checkpoint), now);
        }
        appendWorkflowSteps(thread, turn, run.runId(), question == null
                ? LangGraphWorkflowGraphFactory.AUTHORIZE
                : selected == null ? LangGraphWorkflowGraphFactory.RESOLVE_ORDER
                : LangGraphWorkflowGraphFactory.SWITCH_REQUIREMENTS, now);
        return new StartResult(run.runId(), question, checkpoint);
    }

    @Override
    public ResumeResult resume(AgentThreadModel thread, AgentTurnModel turn, Map<String, String> ignoredAnswers) {
        requireDependencies();
        if (turn.questionAnswerInput() != null) {
            return resumeQuestion(thread, turn);
        }
        if (turn.workflowDecisionInput() != null) {
            return resumeDecision(thread, turn);
        }
        throw new AgentThreadConflictException("WORKFLOW_INPUT_MISSING", "Workflow 恢复 Turn 缺少结构化输入");
    }

    private ResumeResult resumeQuestion(AgentThreadModel thread, AgentTurnModel answerTurn) {
        AgentQuestionAnswerInput input = answerTurn.questionAnswerInput();
        AgentQuestionCardModel question = questionCards.find(thread.userId(), input.questionId())
                .orElseThrow(() -> new AgentThreadConflictException("QUESTION_NOT_FOUND", "QuestionCard 不存在"));
        if (!question.threadId().equals(thread.threadId())
                || !java.util.Objects.equals(question.runId(), input.runId())
                || question.version() != input.enqueuedQuestionVersion()
                || !answerTurn.turnId().equals(question.answerTurnId())) {
            throw new AgentThreadConflictException("QUESTION_VERSION_CONFLICT", "QuestionCard 回答版本已变化");
        }
        if (input.action() == AgentQuestionCardAnswerActionEnum.CANCEL) {
            return inTransaction(() -> cancelQuestion(thread, answerTurn, question));
        }
        AgentWorkflowRunModel run = workflowRuns.find(thread.userId(), input.runId())
                .orElseThrow(() -> new IllegalStateException("QuestionCard 对应 WorkflowRun 不存在"));
        WorkflowRequest request = requestFromRun(run);
        String step = questionStep(question);
        if ("ORDER_SELECT".equals(step)) {
            String orderId = required(input.answers(), "orderId");
            request = request.withOrderId(orderId);
        } else if ("REASON".equals(step)) {
            request = request.withReason(bounded(required(input.answers(), "reason"), MAX_REASON_LENGTH));
        }
        ResolvedCandidates candidates = resolveCandidates(request, thread.userId());
        SelectedOrder selected = selectCandidate(request, candidates, thread.userId());
        if (selected != null && !isReasonMissing(request)) {
            requireActionAllowed(request.intent(), selected.order());
        }
        String fingerprint = factsFingerprint(request, candidates, selected);
        Map<String, Object> businessState = state(request, candidates.orders(), selected, request.reason());
        Map<String, Object> graphState = new LinkedHashMap<>(businessState);
        graphState.put("factsDecision", "READY");
        graphState.put("workflowVersion", run.version());
        graphState.put("factsFingerprint", fingerprint);
        Map<String, Object> pausedState = runGraph(run.runId(), graphState, run.version(), fingerprint, true);
        Instant now = clock.instant();
        boolean missingOrder = selected == null;
        boolean missingReason = !missingOrder && isReasonMissing(request);
        AgentQuestionCardModel nextQuestion = missingOrder || missingReason
                ? questionCard(run, answerTurn, request, candidates, selected, missingOrder, now)
                : null;
        AgentWorkflowCheckpointModel nextCheckpoint = nextQuestion == null
                ? checkpoint(run, answerTurn, request, selected, fingerprint, now)
                : null;
        AgentWorkflowRunModel progressed = run.progress(
                steps(nextQuestion == null ? LangGraphWorkflowGraphFactory.AUTHORIZE
                        : missingOrder ? LangGraphWorkflowGraphFactory.RESOLVE_ORDER
                        : LangGraphWorkflowGraphFactory.SWITCH_REQUIREMENTS, "WAITING"),
                writeJson(withGraphState(businessState, pausedState, fingerprint, run.version())), now);
        return inTransaction(() -> persistQuestionResume(thread, answerTurn, question, progressed,
                candidates, selected, nextQuestion, nextCheckpoint, now));
    }

    private ResumeResult persistQuestionResume(
            AgentThreadModel thread,
            AgentTurnModel answerTurn,
            AgentQuestionCardModel answered,
            AgentWorkflowRunModel run,
            ResolvedCandidates candidates,
            SelectedOrder selected,
            AgentQuestionCardModel nextQuestion,
            AgentWorkflowCheckpointModel nextCheckpoint,
            Instant now
    ) {
        if (!questionCards.closeAnswerTurn(thread.userId(), answered.questionId(), answered.version(),
                answerTurn.turnId(), cn.ethan.core.agent.workflow.AgentQuestionCardStatusEnum.ANSWERED, now)) {
            throw new AgentThreadConflictException("QUESTION_VERSION_CONFLICT", "QuestionCard 关闭版本已变化");
        }
        workflowRuns.update(run);
        appendAnswerResult(thread, answerTurn, run.runId(), "ANSWERED", now);
        appendOrderFacts(thread, answerTurn, candidates.orders(), selected, now);
        if (nextQuestion != null) {
            questionCards.create(nextQuestion);
            appendItem(thread, answerTurn, AgentItemTypeEnum.QUESTION_CARD,
                    AgentTurnItemPayloads.questionCard(nextQuestion), now);
            projectOwner(thread, run, AgentTurnStatusEnum.WAITING_USER_INPUT,
                    "还需要补充一项订单信息。", now);
            return new ResumeResult("还需要补充一项订单信息。", "WAITING_USER_INPUT", null, nextQuestion, null);
        }
        checkpoints.create(nextCheckpoint);
        appendItem(thread, answerTurn, AgentItemTypeEnum.WORKFLOW_CHECKPOINT,
                AgentTurnItemPayloads.workflowCheckpoint(nextCheckpoint), now);
        projectOwner(thread, run, AgentTurnStatusEnum.WAITING_USER_INPUT,
                "订单信息已核验，请确认是否执行。", now);
        return new ResumeResult("订单信息已核验，请确认是否执行。", "WAITING_USER_INPUT", null,
                null, nextCheckpoint);
    }

    private ResumeResult cancelQuestion(
            AgentThreadModel thread,
            AgentTurnModel answerTurn,
            AgentQuestionCardModel question
    ) {
        Instant now = clock.instant();
        AgentWorkflowRunModel run = workflowRuns.find(thread.userId(), question.runId())
                .orElseThrow(() -> new IllegalStateException("QuestionCard 对应 WorkflowRun 不存在"));
        if (!questionCards.closeAnswerTurn(thread.userId(), question.questionId(), question.version(),
                answerTurn.turnId(), cn.ethan.core.agent.workflow.AgentQuestionCardStatusEnum.CANCELLED, now)) {
            throw new AgentThreadConflictException("QUESTION_VERSION_CONFLICT", "QuestionCard 关闭版本已变化");
        }
        AgentWorkflowRunModel rejected = run.status(AgentWorkflowStatusEnum.REJECTED,
                steps(LangGraphWorkflowGraphFactory.HANDOFF_AGENT, "COMPLETED"), run.stateJson(), now);
        workflowRuns.update(rejected);
        appendAnswerResult(thread, answerTurn, run.runId(), "CANCELLED", now);
        projectOwner(thread, run, AgentTurnStatusEnum.COMPLETED,
                "本次订单操作已取消，未执行外部动作。", now);
        return new ResumeResult("本次订单操作已取消，未执行外部动作。", "REJECTED", null);
    }

    private ResumeResult resumeDecision(AgentThreadModel thread, AgentTurnModel decisionTurn) {
        AgentWorkflowDecisionInput input = decisionTurn.workflowDecisionInput();
        AgentWorkflowCheckpointModel checkpoint = checkpoints.find(thread.userId(), input.checkpointId())
                .orElseThrow(() -> new AgentThreadConflictException("CHECKPOINT_NOT_FOUND", "Workflow Checkpoint 不存在"));
        if (!checkpoint.threadId().equals(thread.threadId()) || !checkpoint.runId().equals(input.runId())
                || checkpoint.version() != input.expectedVersion() + 1) {
            throw new AgentThreadConflictException("CHECKPOINT_VERSION_CONFLICT", "Workflow Checkpoint 决策版本已变化");
        }
        AgentWorkflowRunModel run = workflowRuns.find(thread.userId(), input.runId())
                .orElseThrow(() -> new IllegalStateException("Workflow Checkpoint 对应 WorkflowRun 不存在"));
        Instant now = clock.instant();
        if (checkpoint.status() == AgentWorkflowCheckpointStatusEnum.SUPERSEDED) {
            return reverifyAfterFactsChanged(thread, decisionTurn, run, checkpoint, now);
        }
        if (input.decision() == AgentWorkflowDecisionEnum.REJECT
                || checkpoint.status() == AgentWorkflowCheckpointStatusEnum.REJECTED) {
            AgentWorkflowRunModel rejected = run.status(AgentWorkflowStatusEnum.REJECTED,
                    steps(LangGraphWorkflowGraphFactory.HANDOFF_AGENT, "COMPLETED"), run.stateJson(), now);
            return inTransaction(() -> {
                workflowRuns.update(rejected);
                appendAnswerResult(thread, decisionTurn, run.runId(), "REJECTED", now);
                appendWorkflowStep(thread, decisionTurn, run.runId(), LangGraphWorkflowGraphFactory.AUTHORIZE,
                        "REJECTED", "REJECT", now);
                projectOwner(thread, run, AgentTurnStatusEnum.COMPLETED,
                        "已拒绝本次订单操作，未执行外部动作。", now);
                return new ResumeResult("已拒绝本次订单操作，未执行外部动作。", "REJECTED", null,
                        null, checkpoint);
            });
        }
        if (checkpoint.status() != AgentWorkflowCheckpointStatusEnum.APPROVED
                || input.decision() != AgentWorkflowDecisionEnum.APPROVE) {
            throw new AgentThreadConflictException("CHECKPOINT_VERSION_CONFLICT", "Workflow Checkpoint 决策状态不一致");
        }
        WorkflowRequest request = requestFromRun(run);
        OrderSnapshotModel order = lookupSelected(checkpoint.orderId(), thread.userId());
        SelectedOrder selected = selectedOrder(order, thread.userId());
        String fingerprint = factsFingerprint(request, new ResolvedCandidates(List.of(order), true), selected);
        if (!checkpoint.factsFingerprint().equals(input.factsFingerprint())
                || !checkpoint.factsFingerprint().equals(fingerprint)) {
            return reverifyAfterFactsChanged(thread, decisionTurn, run, checkpoint, now);
        }
        requireActionAllowed(request.intent(), selected.order());
        Map<String, Object> state = state(request.withOrderId(order.orderId()), List.of(order), selected, request.reason());
        Map<String, Object> graphState = new LinkedHashMap<>(state);
        graphState.put("factsDecision", "READY");
        graphState.put("workflowVersion", run.version());
        graphState.put("factsFingerprint", fingerprint);
        Map<String, Object> completedGraphState = runGraph(run.runId(), graphState, run.version(), fingerprint, true);
        return inTransaction(() -> approve(thread, decisionTurn, run, request, selected, fingerprint,
                completedGraphState, checkpoint, now));
    }

    /**
     * Checkpoint 决策期间事实指纹已变化时，旧 Checkpoint 只能失效，Workflow 回到 VERIFY_FACTS
     * 并以最新事实创建一张新的确认卡，避免把旧批准沿用到新订单状态。
     */
    private ResumeResult reverifyAfterFactsChanged(
            AgentThreadModel thread,
            AgentTurnModel decisionTurn,
            AgentWorkflowRunModel run,
            AgentWorkflowCheckpointModel superseded,
            Instant now
    ) {
        WorkflowRequest request = requestFromRun(run);
        OrderSnapshotModel latest = lookupSelected(superseded.orderId(), thread.userId());
        SelectedOrder selected = selectedOrder(latest, thread.userId());
        String fingerprint = factsFingerprint(request, new ResolvedCandidates(List.of(latest), true), selected);
        Map<String, Object> latestState = state(request.withOrderId(latest.orderId()), List.of(latest), selected,
                request.reason());
        try {
            requireActionAllowed(request.intent(), latest);
        } catch (AgentThreadConflictException actionInvalid) {
            AgentWorkflowRunModel failed = run.status(AgentWorkflowStatusEnum.FAILED,
                    steps(LangGraphWorkflowGraphFactory.VERIFY_FACTS, "FAILED"), writeJson(latestState), now);
            return inTransaction(() -> {
                supersedeApprovedCheckpoint(superseded, thread.userId());
                workflowRuns.update(failed);
                appendAnswerResult(thread, decisionTurn, run.runId(), "FACTS_CHANGED_ACTION_NOT_ALLOWED", now);
                appendOrderFacts(thread, decisionTurn, List.of(latest), selected, now);
                appendWorkflowStep(thread, decisionTurn, run.runId(), LangGraphWorkflowGraphFactory.VERIFY_FACTS,
                        "FAILED", "ACTION_NOT_ALLOWED", now);
                projectOwner(thread, run, AgentTurnStatusEnum.FAILED,
                        "订单事实已更新，当前状态不再允许该操作，未执行外部动作。", now);
                return new ResumeResult("订单事实已更新，当前状态不再允许该操作，未执行外部动作。",
                        "FAILED", null, null, null);
            });
        }
        AgentWorkflowRunModel progressed = run.progress(
                steps(LangGraphWorkflowGraphFactory.VERIFY_FACTS, "WAITING"), writeJson(latestState), now);
        AgentWorkflowCheckpointModel next = checkpoint(progressed, decisionTurn,
                request.withOrderId(latest.orderId()), selected, fingerprint, now);
        return inTransaction(() -> {
            supersedeApprovedCheckpoint(superseded, thread.userId());
            workflowRuns.update(progressed);
            checkpoints.create(next);
            appendAnswerResult(thread, decisionTurn, run.runId(), "FACTS_CHANGED", now);
            appendOrderFacts(thread, decisionTurn, List.of(latest), selected, now);
            appendWorkflowStep(thread, decisionTurn, run.runId(), LangGraphWorkflowGraphFactory.VERIFY_FACTS,
                    "WAITING", "FACTS_CHANGED", now);
            appendItem(thread, decisionTurn, AgentItemTypeEnum.WORKFLOW_CHECKPOINT,
                    AgentTurnItemPayloads.workflowCheckpoint(next), now);
            projectOwner(thread, run, AgentTurnStatusEnum.WAITING_USER_INPUT,
                    "订单事实已更新，请重新确认执行内容。", now);
            return new ResumeResult("订单事实已更新，请重新确认执行内容。", "FACTS_CHANGED", null,
                    null, next);
        });
    }

    private void supersedeApprovedCheckpoint(AgentWorkflowCheckpointModel checkpoint, String userId) {
        if (checkpoint.status() == AgentWorkflowCheckpointStatusEnum.OPEN
                || checkpoint.status() == AgentWorkflowCheckpointStatusEnum.APPROVED) {
            if (!checkpoints.supersede(userId, checkpoint.checkpointId(), checkpoint.version())) {
                throw new AgentThreadConflictException("CHECKPOINT_VERSION_CONFLICT",
                        "事实变化时 Checkpoint 版本已变化");
            }
        }
    }

    private ResumeResult approve(
            AgentThreadModel thread,
            AgentTurnModel decisionTurn,
            AgentWorkflowRunModel run,
            WorkflowRequest request,
            SelectedOrder selected,
            String fingerprint,
            Map<String, Object> graphState,
            AgentWorkflowCheckpointModel checkpoint,
            Instant now
    ) {
        ExternalActionTypeEnum type = actionType(request.intent());
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("orderId", selected.order().orderId());
        if (!request.reason().isBlank()) {
            payload.put("reason", request.reason());
        }
        if (type == ExternalActionTypeEnum.HIDE_ORDER || type == ExternalActionTypeEnum.RESTORE_ORDER) {
            payload.put("visibility", type == ExternalActionTypeEnum.HIDE_ORDER ? "HIDDEN" : "ACTIVE");
        }
        ExternalActionCommandModel draft = new ExternalActionCommandModel(
                "action-" + UUID.randomUUID(), run.runId(), thread.threadId(), run.turnId(), thread.userId(), type,
                "order-service:" + run.runId() + ":" + type.name() + ":" + selected.order().orderId(),
                writeJson(payload), ExternalActionStatusEnum.PENDING, 0, 3, now, null, null, null, null,
                now, now, null);
        ExternalActionCommandModel command = commands.createIfAbsent(draft);
        AgentWorkflowRunModel waiting = run.status(AgentWorkflowStatusEnum.WAITING_EXTERNAL_ACTION,
                steps(LangGraphWorkflowGraphFactory.EXECUTE_ACTION, "ACTIVE"),
                writeJson(withGraphState(state(request, List.of(selected.order()), selected, request.reason()),
                        graphState, fingerprint, run.version())), now);
        workflowRuns.update(waiting);
        appendWorkflowStep(thread, decisionTurn, run.runId(), LangGraphWorkflowGraphFactory.AUTHORIZE,
                "COMPLETED", "APPROVE", now);
        appendWorkflowStep(thread, decisionTurn, run.runId(), LangGraphWorkflowGraphFactory.EXECUTE_ACTION,
                "ACTIVE", type.name(), now);
        appendItem(thread, decisionTurn, AgentItemTypeEnum.EXTERNAL_ACTION_STATUS,
                writeJson(Map.of("commandId", command.commandId(), "runId", command.runId(),
                        "status", command.status().name(), "actionType", command.type().name(),
                        "orderId", selected.order().orderId())), now);
        projectOwner(thread, run, AgentTurnStatusEnum.WAITING_EXTERNAL_ACTION,
                "已确认，订单动作已进入可靠执行队列。", now);
        return new ResumeResult("已确认，订单动作已进入可靠执行队列。", "APPROVED", command,
                null, checkpoint);
    }

    private AgentQuestionCardModel questionCard(
            AgentWorkflowRunModel run,
            AgentTurnModel sourceTurn,
            WorkflowRequest request,
            ResolvedCandidates candidates,
            SelectedOrder selected,
            boolean missingOrder,
            Instant now
    ) {
        List<AgentQuestionFieldModel> fields;
        String title;
        String prompt;
        String step;
        if (missingOrder) {
            List<String> options = candidates.orders().stream().limit(3).map(OrderSnapshotModel::orderId).toList();
            fields = List.of(new AgentQuestionFieldModel("orderId", true, 64, options, true));
            title = "请确认具体订单";
            prompt = options.isEmpty() ? "暂未找到候选订单，请补充订单号。" : "请选择要处理的订单；如果列表中没有，请填写订单号。";
            step = "ORDER_SELECT";
        } else {
            fields = List.of(new AgentQuestionFieldModel("reason", true, MAX_REASON_LENGTH, List.of(), true));
            title = "补充退款原因";
            prompt = "为了让退款记录完整，请补充退款原因。";
            step = "REASON";
        }
        List<Map<String, String>> summary = new ArrayList<>();
        summary.add(Map.of("label", "操作", "value", actionLabel(request.intent())));
        if (selected != null) {
            summary.add(Map.of("label", "订单", "value", selected.order().orderId()));
        } else if (!candidates.orders().isEmpty()) {
            summary.add(Map.of("label", "候选订单", "value", "找到 " + candidates.orders().size() + " 笔候选订单"));
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("step", step);
        schema.put("stepNo", step.equals("ORDER_SELECT") ? 1 : 2);
        schema.put("summary", summary);
        schema.put("fields", fields.stream().map(this::fieldJson).toList());
        return AgentQuestionCardModel.workflow(
                "question-" + UUID.randomUUID(), run.runId(), run.threadId(), sourceTurn.turnId(), run.userId(),
                step.equals("ORDER_SELECT") ? 1 : 2, title, prompt, writeJson(schema), fields, now);
    }

    private AgentWorkflowCheckpointModel checkpoint(
            AgentWorkflowRunModel run,
            AgentTurnModel sourceTurn,
            WorkflowRequest request,
            SelectedOrder selected,
            String factsFingerprint,
            Instant now
    ) {
        String actionType = actionType(request.intent()).name();
        String impact = switch (request.intent()) {
            case REFUND -> "将提交订单退款动作";
            case EXPEDITE -> "将提交催发货请求";
            case HIDE_ORDER -> "只从订单历史中隐藏，不删除交易或物流事实";
            case RESTORE_ORDER -> "将订单恢复到订单历史列表";
            default -> "将提交订单动作";
        };
        return new AgentWorkflowCheckpointModel(
                "checkpoint-" + UUID.randomUUID(), run.runId(), run.threadId(), sourceTurn.turnId(), run.userId(),
                LangGraphWorkflowGraphFactory.AUTHORIZE, actionType, selected.order().orderId(), impact,
                factsFingerprint, 0L, AgentWorkflowCheckpointStatusEnum.OPEN, null, now, null);
    }

    private Map<String, Object> runGraph(
            String runId,
            Map<String, Object> input,
            long workflowVersion,
            String factsFingerprint,
            boolean resume
    ) {
        Map<String, Object> safeInput = new LinkedHashMap<>(input);
        safeInput.put("workflowVersion", workflowVersion);
        safeInput.put("factsFingerprint", factsFingerprint);
        RunnableConfig config = RunnableConfig.builder().threadId(runId).build()
                .updateMetadata(Map.of("workflowVersion", workflowVersion, "factsFingerprint", factsFingerprint));
        try {
            Optional<AgentState> result = resume
                    ? graph.invoke(GraphInput.resume(), config)
                    : graph.invoke(safeInput, config);
            if (result.isPresent()) {
                return new LinkedHashMap<>(result.get().data());
            }
            return graph.lastStateOf(config)
                    .map(snapshot -> (Map<String, Object>) new LinkedHashMap<>(snapshot.state().data()))
                    .orElse(safeInput);
        } catch (RuntimeException resumeFailure) {
            // 技术快照失配时只用业务 WorkflowRun 重建图状态；绝不把快照当作授权事实。
            if (!resume) {
                throw resumeFailure;
            }
            try {
                Optional<AgentState> rebuilt = graph.invoke(safeInput, config);
                return rebuilt.map(state -> (Map<String, Object>) new LinkedHashMap<>(state.data())).orElse(safeInput);
            } catch (RuntimeException rebuildFailure) {
                rebuildFailure.addSuppressed(resumeFailure);
                throw rebuildFailure;
            }
        }
    }

    private void requireDependencies() {
        if (commands == null || workflowRuns == null || orders == null || questionCards == null || checkpoints == null) {
            throw new IllegalStateException("LangGraph Workflow 依赖未完整装配");
        }
    }

    private ResolvedCandidates resolveCandidates(WorkflowRequest request, String userId) {
        if (!request.orderId().isBlank()) {
            return new ResolvedCandidates(List.of(lookupSelected(request.orderId(), userId)), true);
        }
        OrderSearchResultModel result = orders.searchOrders(request.criteria(), userId);
        if (result == null || result.status() == OrderSearchStatusEnum.TEMPORARY_FAILURE) {
            throw new AgentThreadConflictException("ORDER_TEMPORARY_FAILURE", "订单搜索暂时不可用，请稍后重试");
        }
        return new ResolvedCandidates(result.orders() == null ? List.of()
                : result.orders().stream().filter(value -> value != null).limit(MAX_CANDIDATES).toList(), false);
    }

    private OrderSnapshotModel lookupSelected(String orderId, String userId) {
        OrderLookupResultModel result = orders.findOrder(orderId, userId);
        if (result == null || result.status() == OrderLookupStatusEnum.TEMPORARY_FAILURE) {
            throw new AgentThreadConflictException("ORDER_TEMPORARY_FAILURE", "订单信息暂时无法核验，请稍后重试");
        }
        if (result.status() == OrderLookupStatusEnum.ACCESS_DENIED) {
            throw new AgentThreadConflictException("ORDER_NOT_OWNED", "订单不属于当前用户");
        }
        if (result.status() != OrderLookupStatusEnum.FOUND || result.order() == null) {
            throw new AgentThreadConflictException("ORDER_NOT_FOUND", "订单不存在或已不可见");
        }
        return result.order();
    }

    private SelectedOrder selectCandidate(WorkflowRequest request, ResolvedCandidates candidates, String userId) {
        OrderSnapshotModel selected = request.orderId().isBlank()
                ? candidates.orders().size() == 1 ? candidates.orders().get(0) : null
                : candidates.orders().stream().filter(order -> request.orderId().equals(order.orderId())).findFirst().orElse(null);
        return selected == null ? null : selectedOrder(selected, userId);
    }

    private SelectedOrder selectedOrder(OrderSnapshotModel order, String userId) {
        List<LogisticsEventModel> trace = List.of();
        if (logistics != null) {
            List<LogisticsEventModel> result = logistics.findTrace(order.orderId(), userId);
            trace = result == null ? List.of() : result.stream().filter(value -> value != null)
                    .limit(MAX_LOGISTICS).toList();
        }
        return new SelectedOrder(order, trace);
    }

    private void requireActionAllowed(String intent, OrderSnapshotModel order) {
        if (order == null) {
            throw new AgentThreadConflictException("ORDER_REQUIRED", "请先选择具体订单");
        }
        if (REFUND.equals(intent) && !List.of(OrderStatusEnum.PAID, OrderStatusEnum.SHIPPED,
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
                    "status", "SUCCESS", "orders", candidates.stream().limit(MAX_CANDIDATES)
                            .map(this::safeOrder).toList())), now);
        }
        if (selected != null) {
            appendItem(thread, turn, AgentItemTypeEnum.ORDER_DETAIL, writeJson(safeOrder(selected.order())), now);
            appendItem(thread, turn, AgentItemTypeEnum.LOGISTICS_TIMELINE, writeJson(Map.of(
                    "orderId", selected.order().orderId(), "events", selected.events().stream()
                            .map(this::safeLogistics).toList())), now);
        }
    }

    private void appendWorkflowSteps(AgentThreadModel thread, AgentTurnModel turn, String runId,
                                     String activeNode, Instant now) {
        if (turn == null) {
            return;
        }
        int active = LangGraphWorkflowGraphFactory.NODES_FOR_DOCUMENTATION.indexOf(activeNode);
        if (active < 0) {
            active = 0;
        }
        for (int index = 0; index < LangGraphWorkflowGraphFactory.NODES_FOR_DOCUMENTATION.size(); index++) {
            String node = LangGraphWorkflowGraphFactory.NODES_FOR_DOCUMENTATION.get(index);
            String status = index < active ? "COMPLETED" : index == active ? "WAITING" : "PENDING";
            appendWorkflowStep(thread, turn, runId, node, status, node, now);
        }
    }

    private AgentItemModel appendWorkflowStep(AgentThreadModel thread, AgentTurnModel turn, String runId,
                                              String node, String status, String branch, Instant now) {
        return appendItem(thread, turn, AgentItemTypeEnum.WORKFLOW_STEP,
                AgentTurnItemPayloads.workflowStep(runId, node, status, branch, null, 0L), now);
    }

    private AgentItemModel appendAnswerResult(AgentThreadModel thread, AgentTurnModel turn, String runId,
                                              String status, Instant now) {
        return appendItem(thread, turn, AgentItemTypeEnum.WORKFLOW_RESULT,
                writeJson(Map.of("runId", runId, "status", status)), now);
    }

    private AgentItemModel appendItem(AgentThreadModel thread, AgentTurnModel turn, AgentItemTypeEnum type,
                                      String payload, Instant createdAt) {
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
        if (events != null) {
            afterCommit(() -> events.itemCreated(persisted));
        }
        return persisted;
    }

    private void projectOwner(AgentThreadModel thread, AgentWorkflowRunModel run,
                              AgentTurnStatusEnum target, String message, Instant now) {
        if (turns == null) {
            return;
        }
        AgentTurnModel owner = turns.findTurn(thread.userId(), run.turnId()).orElse(null);
        if (owner == null || isTerminal(owner.status())) {
            return;
        }
        AgentTurnModel next = target == AgentTurnStatusEnum.WAITING_USER_INPUT
                || target == AgentTurnStatusEnum.WAITING_EXTERNAL_ACTION
                ? owner.workflow(run.runId(), target)
                : owner.terminal(target, target == AgentTurnStatusEnum.FAILED ? "WORKFLOW_FAILED" : null, now);
        if (!turns.updateTurn(owner, next)) {
            throw new AgentThreadConflictException("TURN_VERSION_CONFLICT", "Workflow owner Turn 版本竞争");
        }
        if (message != null && !message.isBlank()) {
            appendItem(thread, next, AgentItemTypeEnum.WORKFLOW_RESULT,
                    writeJson(Map.of("runId", run.runId(), "status", target.name(), "message", message)), now);
        }
        appendItem(thread, next, AgentItemTypeEnum.TURN_STATE,
                AgentTurnItemPayloads.turnState(target, null), now);
    }

    private boolean isTerminal(AgentTurnStatusEnum status) {
        return status == AgentTurnStatusEnum.COMPLETED || status == AgentTurnStatusEnum.FAILED
                || status == AgentTurnStatusEnum.CANCELLED || status == AgentTurnStatusEnum.TIMED_OUT;
    }

    private Map<String, Object> state(WorkflowRequest request, List<OrderSnapshotModel> candidates,
                                     SelectedOrder selected, String reason) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("intent", request.intent());
        result.put("orderId", selected == null ? request.orderId() : selected.order().orderId());
        result.put("reason", reason == null || reason.isBlank() ? request.reason() : reason);
        result.put("criteria", request.criteriaValues());
        result.put("candidateOrderIds", candidates == null ? List.of()
                : candidates.stream().limit(MAX_CANDIDATES).map(OrderSnapshotModel::orderId).toList());
        if (selected != null) {
            result.put("selectedOrder", safeOrder(selected.order()));
            result.put("logistics", selected.events().stream().map(this::safeLogistics).toList());
        }
        return result;
    }

    private Map<String, Object> withGraphState(Map<String, Object> business,
                                               Map<String, Object> graphState,
                                               String fingerprint,
                                               long version) {
        Map<String, Object> result = new LinkedHashMap<>(business);
        if (graphState != null) {
            result.put("graphLastNode", graphState.get("lastNode"));
        }
        result.put("workflowVersion", version);
        result.put("factsFingerprint", fingerprint);
        return result;
    }

    private WorkflowRequest requestFromRun(AgentWorkflowRunModel run) {
        try {
            JsonNode root = objectMapper.readTree(run.stateJson());
            Map<String, String> criteria = new LinkedHashMap<>();
            JsonNode criteriaNode = root.path("criteria");
            if (criteriaNode.isObject()) {
                criteriaNode.properties().forEach(entry -> criteria.put(entry.getKey(), entry.getValue().asString("")));
            }
            return new WorkflowRequest(normalizeIntent(root.path("intent").asString("")),
                    root.path("orderId").asString(""), root.path("reason").asString(""), criteria, run.userId());
        } catch (RuntimeException failure) {
            throw new IllegalStateException("无法恢复订单 Workflow 状态", failure);
        }
    }

    private String questionStep(AgentQuestionCardModel question) {
        try {
            JsonNode root = objectMapper.readTree(question.fieldsJson());
            return root.path("step").asString("").trim().toUpperCase(Locale.ROOT);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("无法解析 QuestionCard 步骤", failure);
        }
    }

    private String factsFingerprint(WorkflowRequest request, ResolvedCandidates candidates, SelectedOrder selected) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("intent", request.intent());
        facts.put("orderId", selected == null ? request.orderId() : selected.order().orderId());
        facts.put("reason", request.reason());
        facts.put("orders", candidates.orders().stream().map(this::safeOrder).toList());
        if (selected != null) {
            facts.put("logistics", selected.events().stream().map(this::safeLogistics).toList());
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormatSupport.hex(digest.digest(writeJson(facts).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("JDK 缺少 SHA-256", failure);
        }
    }

    private String steps(String activeNode, String activeStatus) {
        List<Map<String, String>> result = new ArrayList<>();
        int active = LangGraphWorkflowGraphFactory.NODES_FOR_DOCUMENTATION.indexOf(activeNode);
        if (active < 0) {
            active = 0;
        }
        for (int index = 0; index < LangGraphWorkflowGraphFactory.NODES_FOR_DOCUMENTATION.size(); index++) {
            String node = LangGraphWorkflowGraphFactory.NODES_FOR_DOCUMENTATION.get(index);
            result.add(Map.of("node", node, "status", index < active ? "COMPLETED"
                    : index == active ? activeStatus : "PENDING"));
        }
        return writeJson(result);
    }

    private Map<String, Object> safeOrder(OrderSnapshotModel order) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("orderId", order.orderId());
        value.put("status", order.status().name());
        put(value, "createdAt", order.createdAt());
        put(value, "expectedDeliveryAt", order.expectedDeliveryAt());
        put(value, "lastLogisticsAt", order.lastLogisticsAt());
        put(value, "logisticsStatus", order.logisticsStatus());
        put(value, "paidAmount", order.paidAmount() == null ? null : order.paidAmount().toPlainString());
        put(value, "currency", order.currency());
        put(value, "itemSummary", order.itemSummary());
        value.put("visibility", order.hiddenAt() == null ? "ACTIVE" : "HIDDEN");
        return value;
    }

    private Map<String, Object> safeLogistics(LogisticsEventModel event) {
        return Map.of("eventId", event.eventId(), "status", event.status(), "location", event.location(),
                "description", event.description(), "occurredAt", event.occurredAt().toString());
    }

    private Map<String, Object> fieldJson(AgentQuestionFieldModel field) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("name", field.name());
        value.put("required", field.required());
        value.put("maxLength", field.maxLength());
        value.put("options", field.options());
        value.put("allowCustom", field.allowCustom());
        return value;
    }

    private void put(Map<String, Object> target, String key, Object value) {
        if (value != null && (!(value instanceof String string) || !string.isBlank())) {
            target.put(key, value instanceof Instant instant ? instant.toString() : value);
        }
    }

    private String actionLabel(String intent) {
        return switch (normalizeIntent(intent)) {
            case REFUND -> "退款";
            case EXPEDITE -> "催发货";
            case HIDE_ORDER -> "隐藏订单记录";
            case RESTORE_ORDER -> "恢复订单记录";
            default -> "订单操作";
        };
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception failure) {
            throw new IllegalStateException("无法编码订单 Workflow 状态", failure);
        }
    }

    private <T> T inTransaction(Supplier<T> work) {
        if (transactionTemplate == null) {
            return work.get();
        }
        T value = transactionTemplate.execute(status -> work.get());
        if (value == null) {
            throw new IllegalStateException("Workflow 事务未返回结果");
        }
        return value;
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

    private static String required(Map<String, String> answers, String key) {
        String value = answers == null ? "" : answers.getOrDefault(key, "");
        if (value == null || value.trim().isBlank()) {
            throw new AgentThreadConflictException("QUESTION_ANSWER_INVALID", "缺少回答字段：" + key);
        }
        return value.trim();
    }

    private boolean isReasonMissing(WorkflowRequest request) {
        return REFUND.equals(request.intent()) && request.reason().isBlank();
    }

    private String bounded(String value, int max) {
        String normalized = value == null ? "" : value.trim();
        return normalized.length() <= max ? normalized : normalized.substring(0, max);
    }

    private static String normalizeIntent(String value) {
        if (value == null) {
            return "";
        }
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "REFUND", "退款" -> REFUND;
            case "EXPEDITE", "催发货", "催单" -> EXPEDITE;
            case "HIDE_ORDER", "HIDE", "隐藏订单", "隐藏" -> HIDE_ORDER;
            case "RESTORE_ORDER", "RESTORE", "恢复订单", "恢复" -> RESTORE_ORDER;
            default -> value.trim().toUpperCase(Locale.ROOT);
        };
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
            return normalizeIntent(explicit);
        }
        String normalized = normalizeIntent(operation);
        return List.of(REFUND, EXPEDITE, HIDE_ORDER, RESTORE_ORDER).contains(normalized) ? normalized : "";
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
                .map(String::trim).filter(item -> !item.isBlank())
                .map(item -> OrderStatusEnum.valueOf(item.toUpperCase(Locale.ROOT)))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static OrderVisibilityEnum parseVisibility(String value) {
        return value == null || value.isBlank() ? OrderVisibilityEnum.ACTIVE
                : OrderVisibilityEnum.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    private record ResolvedCandidates(List<OrderSnapshotModel> orders, boolean exact) {
        private ResolvedCandidates {
            orders = orders == null ? List.of() : List.copyOf(orders);
        }
    }

    private record SelectedOrder(OrderSnapshotModel order, List<LogisticsEventModel> events) {
        private SelectedOrder {
            events = events == null ? List.of() : List.copyOf(events);
        }
    }

    private record WorkflowRequest(String intent, String orderId, String reason,
                                   Map<String, String> criteriaValues, String userId) {
        private WorkflowRequest {
            intent = normalizeIntent(intent);
            orderId = orderId == null ? "" : orderId.trim();
            reason = reason == null ? "" : reason.trim();
            criteriaValues = criteriaValues == null ? Map.of() : Map.copyOf(criteriaValues);
            userId = userId == null ? "" : userId;
        }

        private static WorkflowRequest from(String operation, Map<String, String> arguments) {
            Map<String, String> criteria = new LinkedHashMap<>();
            for (String key : List.of("createdFrom", "createdTo", "minAmount", "maxAmount", "statuses",
                    "keyword", "logisticsStalledDays", "visibility")) {
                String value = LangGraphAgentWorkflowEngine.value(arguments, key);
                if (!value.isBlank()) {
                    criteria.put(key, value);
                }
            }
            return new WorkflowRequest(parseIntent(operation, arguments), value(arguments, "orderId"),
                    value(arguments, "reason"), criteria, "");
        }

        private WorkflowRequest withOrderId(String nextOrderId) {
            return new WorkflowRequest(intent, nextOrderId, reason, criteriaValues, userId);
        }

        private WorkflowRequest withReason(String nextReason) {
            return new WorkflowRequest(intent, orderId, nextReason, criteriaValues, userId);
        }

        private OrderSearchCriteria criteria() {
            String stalled = criteriaValues.getOrDefault("logisticsStalledDays", "");
            return new OrderSearchCriteria(
                    parseBoundary("createdFrom", criteriaValues.getOrDefault("createdFrom", ""), false),
                    parseBoundary("createdTo", criteriaValues.getOrDefault("createdTo", ""), true),
                    parseAmount("minAmount", criteriaValues.getOrDefault("minAmount", "")),
                    parseAmount("maxAmount", criteriaValues.getOrDefault("maxAmount", "")),
                    parseStatuses(criteriaValues.getOrDefault("statuses", "")),
                    criteriaValues.getOrDefault("keyword", ""),
                    stalled.isBlank() ? null : Integer.parseInt(stalled),
                    parseVisibility(criteriaValues.getOrDefault("visibility", "ACTIVE")), MAX_CANDIDATES);
        }
    }

    private static final class HexFormatSupport {
        private static String hex(byte[] bytes) {
            StringBuilder value = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) {
                value.append(String.format("%02x", item));
            }
            return value.toString();
        }
    }
}
