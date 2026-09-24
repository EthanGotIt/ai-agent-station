package cn.ethan.infrastructure.agent.workflow.java;

import cn.ethan.core.agent.action.ExternalActionCommandModel;
import cn.ethan.core.agent.action.ExternalActionCommandStore;
import cn.ethan.core.agent.action.ExternalActionStatusEnum;
import cn.ethan.core.agent.action.ExternalActionTypeEnum;
import cn.ethan.core.agent.event.AgentThreadEventGateway;
import cn.ethan.core.agent.execution.AgentTurnItemPayloads;
import cn.ethan.core.agent.thread.AgentItemJournal;
import cn.ethan.core.agent.thread.AgentItemModel;
import cn.ethan.core.agent.thread.AgentItemPayloadCodec;
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
import cn.ethan.core.agent.workflow.AgentQuestionCardStatusEnum;
import cn.ethan.core.agent.workflow.AgentQuestionCardStore;
import cn.ethan.core.agent.workflow.AgentQuestionFieldModel;
import cn.ethan.core.agent.workflow.AgentWorkflowCheckpointModel;
import cn.ethan.core.agent.workflow.AgentWorkflowCheckpointStatusEnum;
import cn.ethan.core.agent.workflow.AgentWorkflowCheckpointStore;
import cn.ethan.core.agent.workflow.AgentWorkflowDecisionEnum;
import cn.ethan.core.agent.workflow.AgentWorkflowEngine;
import cn.ethan.core.agent.workflow.AgentWorkflowOrchestrationVersionEnum;
import cn.ethan.core.agent.workflow.AgentWorkflowRunModel;
import cn.ethan.core.agent.workflow.AgentWorkflowRunStore;
import cn.ethan.core.agent.workflow.AgentWorkflowStatusEnum;
import cn.ethan.core.agent.workflow.AgentWorkflowTypeEnum;
import cn.ethan.core.agent.workflow.ExpediteJavaWorkflowPolicy;
import cn.ethan.core.commerce.order.OrderGateway;
import cn.ethan.core.commerce.order.OrderLookupResultModel;
import cn.ethan.core.commerce.order.OrderLookupStatusEnum;
import cn.ethan.core.commerce.order.OrderSearchCriteria;
import cn.ethan.core.commerce.order.OrderSearchResultModel;
import cn.ethan.core.commerce.order.OrderSearchStatusEnum;
import cn.ethan.core.commerce.order.OrderSnapshotModel;
import cn.ethan.core.commerce.order.OrderStatusEnum;
import cn.ethan.infrastructure.agent.workflow.transaction.OrderWorkflowStepProjection;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 类型职责：以持久化 WorkflowRun、QuestionCard、Checkpoint 和 Command 驱动催发货 Java 状态机。
 *
 * <p>状态恢复只读取业务 Run，不依赖图快照。订单读取发生在本地事务外，命令创建则在锁定并复核
 * Run 与 Checkpoint 后提交。</p>
 *
 * @author ethan
 * @date 2026-09-24
 */
@Component("javaExpediteWorkflowEngine")
public final class JavaExpediteWorkflowEngine implements AgentWorkflowEngine {

    private static final String INTENT = "EXPEDITE";
    private static final String ACTION = "EXPEDITE";
    private static final String RESOLVE_ORDER = "RESOLVE_ORDER";
    private static final String AUTHORIZE = "AUTHORIZE";
    private static final String REVERIFY_FACTS = "REVERIFY_FACTS";
    private static final String BUILD_ACTION_COMMAND = "BUILD_ACTION_COMMAND";
    private static final String HANDOFF_WORKER = "HANDOFF_WORKER";
    private static final int MAX_CANDIDATES = 20;

    private final Clock clock;
    private final ExternalActionCommandStore commands;
    private final ObjectMapper objectMapper;
    private final AgentWorkflowRunStore runs;
    private final OrderGateway orders;
    private final AgentItemStore items;
    private final AgentItemJournal journal;
    private final AgentItemPayloadCodec payloadCodec;
    private final AgentTurnStore turns;
    private final AgentThreadEventGateway events;
    private final AgentQuestionCardStore questions;
    private final AgentWorkflowCheckpointStore checkpoints;
    private final TransactionTemplate transactions;
    private final ExpediteJavaWorkflowPolicy policy = new ExpediteJavaWorkflowPolicy();

    @Autowired
    public JavaExpediteWorkflowEngine(
            Clock clock,
            ExternalActionCommandStore commands,
            ObjectMapper objectMapper,
            AgentWorkflowRunStore runs,
            OrderGateway orders,
            AgentItemStore items,
            AgentItemJournal journal,
            AgentItemPayloadCodec payloadCodec,
            AgentTurnStore turns,
            AgentThreadEventGateway events,
            AgentQuestionCardStore questions,
            AgentWorkflowCheckpointStore checkpoints,
            PlatformTransactionManager transactionManager
    ) {
        this.clock = clock;
        this.commands = commands;
        this.objectMapper = objectMapper;
        this.runs = runs;
        this.orders = orders;
        this.items = items;
        this.journal = journal;
        this.payloadCodec = payloadCodec;
        this.turns = turns;
        this.events = events;
        this.questions = questions;
        this.checkpoints = checkpoints;
        this.transactions = transactionManager == null ? null : new TransactionTemplate(transactionManager);
    }

    @Override
    public StartResult start(AgentThreadModel thread, AgentTurnModel turn, String operation,
                             Map<String, String> arguments) {
        Request request = Request.from(operation, arguments);
        Optional<AgentWorkflowRunModel> existing = runs.findBySource(
                thread.userId(), turn.turnId(), AgentWorkflowTypeEnum.ORDER_SERVICE);
        if (existing.isPresent()) {
            AgentWorkflowRunModel run = existing.get();
            requireJavaRun(run);
            requireSameRequest(request, run);
            return existingStart(run);
        }

        Resolved resolved = resolveInitial(request, thread.userId());
        Instant now = clock.instant();
        String runId = "workflow-" + UUID.randomUUID();
        String fingerprint = resolved.order() == null ? "" : policy.factsFingerprint(resolved.order(), request.reason());
        Map<String, Object> state = state(request, resolved.order(), fingerprint);
        String activeNode = resolved.order() == null ? RESOLVE_ORDER : AUTHORIZE;
        AgentWorkflowRunModel run = new AgentWorkflowRunModel(runId, thread.threadId(), turn.turnId(),
                thread.userId(), AgentWorkflowTypeEnum.ORDER_SERVICE, AgentWorkflowStatusEnum.WAITING_USER_INPUT,
                0, snapshot(activeNode, "WAITING"), json(state), now, now,
                AgentWorkflowOrchestrationVersionEnum.EXPEDITE_JAVA_V1);
        AgentQuestionCardModel question = resolved.order() == null
                ? orderQuestion(run, turn, resolved.candidates(), now) : null;
        AgentWorkflowCheckpointModel checkpoint = resolved.order() == null ? null
                : checkpoint(run, turn, resolved.order(), fingerprint, now);
        try {
            return inTransaction(() -> persistStart(thread, turn, run, resolved, question, checkpoint, now));
        } catch (RuntimeException failure) {
            if (!isSourceUniquenessFailure(failure)) {
                throw failure;
            }
            AgentWorkflowRunModel winner = runs.findBySource(thread.userId(), turn.turnId(),
                    AgentWorkflowTypeEnum.ORDER_SERVICE).orElseThrow(() -> failure);
            requireJavaRun(winner);
            requireSameRequest(request, winner);
            return existingStart(winner);
        }
    }

    @Override
    public ResumeResult resume(AgentThreadModel thread, AgentTurnModel turn, Map<String, String> ignoredAnswers) {
        if (turn.questionAnswerInput() != null) {
            return resumeQuestion(thread, turn, turn.questionAnswerInput());
        }
        if (turn.workflowDecisionInput() != null) {
            return resumeDecision(thread, turn, turn.workflowDecisionInput());
        }
        throw conflict("WORKFLOW_INPUT_MISSING", "Workflow 恢复 Turn 缺少结构化输入");
    }

    private StartResult persistStart(AgentThreadModel thread, AgentTurnModel turn, AgentWorkflowRunModel run,
                                     Resolved resolved, AgentQuestionCardModel question,
                                     AgentWorkflowCheckpointModel checkpoint, Instant now) {
        requireNoOpenInteraction(thread.userId(), thread.threadId());
        runs.create(run);
        append(thread, turn, AgentItemTypeEnum.WORKFLOW_STARTED, Map.of("runId", run.runId(),
                "workflowType", "ORDER_SERVICE", "orchestrationVersion", run.orchestrationVersion().name()), now);
        appendOrderFacts(thread, turn, resolved.candidates(), resolved.order(), now);
        if (question != null) {
            questions.create(question);
            append(thread, turn, AgentItemTypeEnum.QUESTION_CARD,
                    AgentTurnItemPayloads.questionCard(question), AgentTurnItemPayloads.questionCardValue(question), now);
            appendSteps(thread, turn, run.runId(), RESOLVE_ORDER, "WAITING", now);
        } else {
            checkpoints.create(checkpoint);
            append(thread, turn, AgentItemTypeEnum.WORKFLOW_CHECKPOINT,
                    AgentTurnItemPayloads.workflowCheckpoint(checkpoint),
                    AgentTurnItemPayloads.workflowCheckpointValue(checkpoint), now);
            appendSteps(thread, turn, run.runId(), AUTHORIZE, "WAITING", now);
        }
        return new StartResult(run.runId(), question, checkpoint);
    }

    private Resolved resolveInitial(Request request, String userId) {
        if (!request.requestOrderId().isBlank()) {
            OrderSnapshotModel selected = lookup(request.requestOrderId(), userId);
            requireEligible(selected, userId);
            return new Resolved(List.of(selected), selected);
        }
        OrderSearchResultModel result = orders.searchOrders(request.criteria(), userId);
        if (result == null || result.status() != OrderSearchStatusEnum.SUCCESS) {
            throw conflict("ORDER_TEMPORARY_FAILURE", "订单信息暂时无法核验，请稍后重试");
        }
        List<OrderSnapshotModel> candidates = result.orders().stream().filter(order -> order != null)
                .limit(MAX_CANDIDATES).toList();
        if (candidates.size() == 1) {
            requireEligible(candidates.get(0), userId);
            return new Resolved(candidates, candidates.get(0));
        }
        return new Resolved(candidates, null);
    }

    private ResumeResult resumeQuestion(AgentThreadModel thread, AgentTurnModel answerTurn,
                                        AgentQuestionAnswerInput input) {
        AgentQuestionCardModel question = questions.find(thread.userId(), input.questionId())
                .orElseThrow(() -> conflict("QUESTION_NOT_FOUND", "QuestionCard 不存在"));
        requireQuestionIdentity(thread, answerTurn, input, question);
        AgentWorkflowRunModel run = runs.find(thread.userId(), input.runId())
                .orElseThrow(() -> conflict("WORKFLOW_NOT_FOUND", "WorkflowRun 不存在"));
        requireJavaRun(run);
        if (run.status() != AgentWorkflowStatusEnum.WAITING_USER_INPUT) {
            throw conflict("WORKFLOW_VERSION_CONFLICT", "Workflow 当前不等待用户补充信息");
        }
        Instant now = clock.instant();
        if (input.action() == AgentQuestionCardAnswerActionEnum.CANCEL) {
            return cancelQuestion(thread, answerTurn, question, run, now);
        }
        Map<String, String> answers = question.validateAnswers(input.answers());
        String orderId = required(answers, "orderId");
        OrderSnapshotModel order = lookup(orderId, thread.userId());
        requireEligible(order, thread.userId());
        String fingerprint = policy.factsFingerprint(order, requestFrom(run).reason());
        Request request = requestFrom(run).withResolvedOrder(order.orderId());
        Map<String, Object> nextState = state(request, order, fingerprint);
        AgentWorkflowRunModel progressed = run.progress(snapshot(AUTHORIZE, "WAITING"), json(nextState), now);
        AgentWorkflowCheckpointModel nextCheckpoint = checkpoint(progressed, answerTurn, order, fingerprint, now);
        return inTransaction(() -> {
            if (!questions.closeAnswerTurn(thread.userId(), question.questionId(), question.version(),
                    answerTurn.turnId(), AgentQuestionCardStatusEnum.ANSWERED, now)) {
                throw conflict("QUESTION_VERSION_CONFLICT", "QuestionCard 回答版本已变化");
            }
            runs.update(progressed);
            appendResult(thread, answerTurn, run.runId(), "ANSWERED", null, now);
            appendOrderFacts(thread, answerTurn, List.of(order), order, now);
            checkpoints.create(nextCheckpoint);
            append(thread, answerTurn, AgentItemTypeEnum.WORKFLOW_CHECKPOINT,
                    AgentTurnItemPayloads.workflowCheckpoint(nextCheckpoint),
                    AgentTurnItemPayloads.workflowCheckpointValue(nextCheckpoint), now);
            appendStep(thread, answerTurn, run.runId(), AUTHORIZE, "WAITING", "ORDER_SELECTED", now);
            projectOwner(thread, run, AgentTurnStatusEnum.WAITING_USER_INPUT,
                    "订单信息已核验，请确认是否执行。", now);
            return new ResumeResult("订单信息已核验，请确认是否执行。", "WAITING_USER_INPUT", null,
                    null, nextCheckpoint);
        });
    }

    private ResumeResult cancelQuestion(AgentThreadModel thread, AgentTurnModel answerTurn,
                                        AgentQuestionCardModel question, AgentWorkflowRunModel run, Instant now) {
        AgentWorkflowRunModel rejected = run.status(AgentWorkflowStatusEnum.REJECTED,
                snapshot(HANDOFF_WORKER, "COMPLETED"), run.stateJson(), now);
        return inTransaction(() -> {
            if (!questions.closeAnswerTurn(thread.userId(), question.questionId(), question.version(),
                    answerTurn.turnId(), AgentQuestionCardStatusEnum.CANCELLED, now)) {
                throw conflict("QUESTION_VERSION_CONFLICT", "QuestionCard 取消版本已变化");
            }
            runs.update(rejected);
            appendResult(thread, answerTurn, run.runId(), "CANCELLED", "本次催发货已取消，未执行外部动作。", now);
            projectOwner(thread, run, AgentTurnStatusEnum.COMPLETED,
                    "本次催发货已取消，未执行外部动作。", now);
            return new ResumeResult("本次催发货已取消，未执行外部动作。", "REJECTED", null);
        });
    }

    private ResumeResult resumeDecision(AgentThreadModel thread, AgentTurnModel decisionTurn,
                                        AgentWorkflowDecisionInput input) {
        AgentWorkflowRunModel run = runs.find(thread.userId(), input.runId())
                .orElseThrow(() -> conflict("WORKFLOW_NOT_FOUND", "WorkflowRun 不存在"));
        requireJavaRun(run);
        AgentWorkflowCheckpointModel checkpoint = checkpoints.find(thread.userId(), input.checkpointId())
                .orElseThrow(() -> conflict("CHECKPOINT_NOT_FOUND", "Workflow Checkpoint 不存在"));
        if (!checkpoint.threadId().equals(thread.threadId()) || !checkpoint.runId().equals(run.runId())
                || checkpoint.version() != input.expectedVersion() + 1) {
            throw conflict("CHECKPOINT_VERSION_CONFLICT", "Workflow Checkpoint 决策版本已变化");
        }
        Optional<ExternalActionCommandModel> existing = commands.findByRunId(thread.userId(), run.runId());
        if (input.decision() == AgentWorkflowDecisionEnum.APPROVE && existing.isPresent()) {
            return new ResumeResult("已确认，催发货动作已进入可靠执行队列。", "APPROVED",
                    existing.get(), null, checkpoint);
        }
        Instant now = clock.instant();
        if (input.decision() == AgentWorkflowDecisionEnum.REJECT) {
            return rejectDecision(thread, decisionTurn, run, checkpoint, now);
        }
        if (checkpoint.status() != AgentWorkflowCheckpointStatusEnum.APPROVED
                && checkpoint.status() != AgentWorkflowCheckpointStatusEnum.SUPERSEDED) {
            throw conflict("CHECKPOINT_VERSION_CONFLICT", "Workflow Checkpoint 尚未批准");
        }
        OrderSnapshotModel latest;
        try {
            latest = lookup(checkpoint.orderId(), thread.userId());
        } catch (RuntimeException failure) {
            return failReverification(thread, decisionTurn, run, checkpoint, now,
                    "订单事实暂时无法核验，未执行催发货，请重新发起操作。");
        }
        if (!policy.eligible(latest, thread.userId())) {
            return failReverification(thread, decisionTurn, run, checkpoint, now,
                    "订单当前状态不再允许催发货，未执行外部动作。");
        }
        String fingerprint = policy.factsFingerprint(latest, requestFrom(run).reason());
        if (checkpoint.status() == AgentWorkflowCheckpointStatusEnum.SUPERSEDED
                || !checkpoint.factsFingerprint().equals(fingerprint)
                || !checkpoint.factsFingerprint().equals(input.factsFingerprint())) {
            return reconfirm(thread, decisionTurn, run, checkpoint, latest, fingerprint, now);
        }
        return approve(thread, decisionTurn, run, checkpoint, latest, fingerprint, now);
    }

    private ResumeResult rejectDecision(AgentThreadModel thread, AgentTurnModel turn, AgentWorkflowRunModel run,
                                         AgentWorkflowCheckpointModel checkpoint, Instant now) {
        if (checkpoint.status() != AgentWorkflowCheckpointStatusEnum.REJECTED) {
            throw conflict("CHECKPOINT_VERSION_CONFLICT", "Workflow Checkpoint 未记录拒绝决策");
        }
        if (run.status() == AgentWorkflowStatusEnum.REJECTED) {
            return new ResumeResult("已拒绝催发货，未执行外部动作。", "REJECTED", null, null, checkpoint);
        }
        AgentWorkflowRunModel rejected = run.status(AgentWorkflowStatusEnum.REJECTED,
                snapshot(HANDOFF_WORKER, "COMPLETED"), run.stateJson(), now);
        return inTransaction(() -> {
            runs.update(rejected);
            appendResult(thread, turn, run.runId(), "REJECTED", "已拒绝催发货，未执行外部动作。", now);
            appendStep(thread, turn, run.runId(), AUTHORIZE, "REJECTED", "REJECT", now);
            projectOwner(thread, run, AgentTurnStatusEnum.COMPLETED,
                    "已拒绝催发货，未执行外部动作。", now);
            return new ResumeResult("已拒绝催发货，未执行外部动作。", "REJECTED", null, null, checkpoint);
        });
    }

    private ResumeResult reconfirm(AgentThreadModel thread, AgentTurnModel turn, AgentWorkflowRunModel run,
                                   AgentWorkflowCheckpointModel old, OrderSnapshotModel latest,
                                   String fingerprint, Instant now) {
        Request request = requestFrom(run).withResolvedOrder(latest.orderId());
        AgentWorkflowRunModel progressed = run.progress(snapshot(REVERIFY_FACTS, "WAITING"),
                json(state(request, latest, fingerprint)), now);
        AgentWorkflowCheckpointModel next = checkpoint(progressed, turn, latest, fingerprint, now);
        return inTransaction(() -> {
            AgentWorkflowRunModel lockedRun = lockRun(thread, run);
            AgentWorkflowCheckpointModel lockedCheckpoint = lockCheckpoint(thread, old);
            requireUnchanged(lockedRun, run, lockedCheckpoint, old);
            supersede(lockedCheckpoint, thread.userId());
            runs.update(progressed);
            checkpoints.create(next);
            appendResult(thread, turn, run.runId(), "FACTS_CHANGED", "订单事实已更新，请重新确认执行内容。", now);
            appendOrderFacts(thread, turn, List.of(latest), latest, now);
            appendStep(thread, turn, run.runId(), REVERIFY_FACTS, "COMPLETED", "FACTS_CHANGED", now);
            append(thread, turn, AgentItemTypeEnum.WORKFLOW_CHECKPOINT,
                    AgentTurnItemPayloads.workflowCheckpoint(next),
                    AgentTurnItemPayloads.workflowCheckpointValue(next), now);
            projectOwner(thread, run, AgentTurnStatusEnum.WAITING_USER_INPUT,
                    "订单事实已更新，请重新确认执行内容。", now);
            return new ResumeResult("订单事实已更新，请重新确认执行内容。", "FACTS_CHANGED", null, null, next);
        });
    }

    private ResumeResult failReverification(AgentThreadModel thread, AgentTurnModel turn,
                                            AgentWorkflowRunModel run, AgentWorkflowCheckpointModel checkpoint,
                                            Instant now, String message) {
        AgentWorkflowRunModel failed = run.status(AgentWorkflowStatusEnum.FAILED,
                snapshot(REVERIFY_FACTS, "ERROR"), run.stateJson(), now);
        return inTransaction(() -> {
            AgentWorkflowRunModel lockedRun = lockRun(thread, run);
            AgentWorkflowCheckpointModel lockedCheckpoint = lockCheckpoint(thread, checkpoint);
            requireUnchanged(lockedRun, run, lockedCheckpoint, checkpoint);
            supersede(lockedCheckpoint, thread.userId());
            runs.update(failed);
            appendResult(thread, turn, run.runId(), "FACTS_CHANGED_ACTION_NOT_ALLOWED", message, now);
            appendStep(thread, turn, run.runId(), REVERIFY_FACTS, "ERROR", "NOT_ALLOWED", now);
            projectOwner(thread, run, AgentTurnStatusEnum.FAILED, message, now);
            return new ResumeResult(message, "FAILED", null);
        });
    }

    private ResumeResult approve(AgentThreadModel thread, AgentTurnModel turn, AgentWorkflowRunModel run,
                                 AgentWorkflowCheckpointModel checkpoint, OrderSnapshotModel order,
                                 String fingerprint, Instant now) {
        return inTransaction(() -> {
            AgentWorkflowRunModel lockedRun = lockRun(thread, run);
            AgentWorkflowCheckpointModel lockedCheckpoint = lockCheckpoint(thread, checkpoint);
            if (lockedRun.orchestrationVersion() != AgentWorkflowOrchestrationVersionEnum.EXPEDITE_JAVA_V1
                    || lockedRun.version() != run.version()
                    || lockedCheckpoint.version() != checkpoint.version()
                    || lockedCheckpoint.status() != AgentWorkflowCheckpointStatusEnum.APPROVED
                    || !lockedCheckpoint.factsFingerprint().equals(fingerprint)) {
                throw conflict("CHECKPOINT_VERSION_CONFLICT", "批准期间 Workflow 事实或版本已变化");
            }
            Optional<ExternalActionCommandModel> prior = commands.findByRunId(thread.userId(), run.runId());
            if (prior.isPresent()) {
                return new ResumeResult("已确认，催发货动作已进入可靠执行队列。", "APPROVED",
                        prior.get(), null, lockedCheckpoint);
            }
            String idempotencyKey = "order-service:" + lockedRun.runId() + ":EXPEDITE:" + order.orderId();
            Map<String, String> payload = new LinkedHashMap<>();
            payload.put("orderId", order.orderId());
            String reason = requestFrom(run).reason();
            if (!reason.isBlank()) payload.put("reason", reason);
            ExternalActionCommandModel draft = new ExternalActionCommandModel(
                    "action-" + UUID.randomUUID(), lockedRun.runId(), thread.threadId(), lockedRun.turnId(),
                    thread.userId(), ExternalActionTypeEnum.EXPEDITE, idempotencyKey, json(payload),
                    ExternalActionStatusEnum.PENDING, 0, 3, now, null, null, null, null, now, now, null);
            ExternalActionCommandModel command = commands.createIfAbsent(draft);
            Request request = requestFrom(run).withResolvedOrder(order.orderId());
            AgentWorkflowRunModel waiting = lockedRun.status(AgentWorkflowStatusEnum.WAITING_EXTERNAL_ACTION,
                    snapshot(HANDOFF_WORKER, "ACTIVE"), json(state(request, order, fingerprint)), now);
            runs.update(waiting);
            appendStep(thread, turn, run.runId(), AUTHORIZE, "COMPLETED", "APPROVE", now);
            appendStep(thread, turn, run.runId(), REVERIFY_FACTS, "COMPLETED", "UNCHANGED", now);
            appendStep(thread, turn, run.runId(), BUILD_ACTION_COMMAND, "COMPLETED", "EXPEDITE", now);
            appendStep(thread, turn, run.runId(), HANDOFF_WORKER, "ACTIVE", "COMMAND_QUEUED", now);
            append(thread, turn, AgentItemTypeEnum.EXTERNAL_ACTION_STATUS,
                    Map.of("commandId", command.commandId(), "runId", command.runId(),
                            "status", command.status().name(), "actionType", command.type().name(),
                            "orderId", order.orderId()),
                    AgentTurnItemPayloads.externalActionStatusValue(command.commandId(), command.runId(),
                            command.status().name(), command.attemptCount(), command.retryCycleAttemptCount(),
                            command.maxAttempts(), command.type().name(), order.orderId(), null, null, null,
                            null, null, null), now);
            projectOwner(thread, lockedRun, AgentTurnStatusEnum.WAITING_EXTERNAL_ACTION,
                    "已确认，催发货动作已进入可靠执行队列。", now);
            return new ResumeResult("已确认，催发货动作已进入可靠执行队列。", "APPROVED",
                    command, null, lockedCheckpoint);
        });
    }

    private AgentWorkflowRunModel lockRun(AgentThreadModel thread, AgentWorkflowRunModel expected) {
        return runs.findForUpdate(thread.userId(), expected.runId())
                .orElseThrow(() -> conflict("WORKFLOW_VERSION_CONFLICT", "WorkflowRun 已不存在"));
    }

    private AgentWorkflowCheckpointModel lockCheckpoint(AgentThreadModel thread,
                                                        AgentWorkflowCheckpointModel expected) {
        return checkpoints.findForUpdate(thread.userId(), expected.checkpointId())
                .orElseThrow(() -> conflict("CHECKPOINT_VERSION_CONFLICT", "Workflow Checkpoint 已不存在"));
    }

    private void requireUnchanged(AgentWorkflowRunModel lockedRun, AgentWorkflowRunModel expectedRun,
                                  AgentWorkflowCheckpointModel lockedCheckpoint,
                                  AgentWorkflowCheckpointModel expectedCheckpoint) {
        if (lockedRun.version() != expectedRun.version()
                || lockedRun.orchestrationVersion() != AgentWorkflowOrchestrationVersionEnum.EXPEDITE_JAVA_V1
                || lockedCheckpoint.version() != expectedCheckpoint.version()
                || !lockedCheckpoint.runId().equals(lockedRun.runId())) {
            throw conflict("CHECKPOINT_VERSION_CONFLICT", "事实核验期间 Workflow 版本已变化");
        }
    }

    private void supersede(AgentWorkflowCheckpointModel checkpoint, String userId) {
        if ((checkpoint.status() == AgentWorkflowCheckpointStatusEnum.OPEN
                || checkpoint.status() == AgentWorkflowCheckpointStatusEnum.APPROVED)
                && !checkpoints.supersede(userId, checkpoint.checkpointId(), checkpoint.version())) {
            throw conflict("CHECKPOINT_VERSION_CONFLICT", "事实变化时 Checkpoint 版本已变化");
        }
    }

    private AgentWorkflowCheckpointModel checkpoint(AgentWorkflowRunModel run, AgentTurnModel turn,
                                                   OrderSnapshotModel order, String fingerprint, Instant now) {
        return new AgentWorkflowCheckpointModel("checkpoint-" + UUID.randomUUID(), run.runId(),
                run.threadId(), turn.turnId(), run.userId(), AUTHORIZE, ACTION, order.orderId(),
                "将对已支付订单 " + order.orderId() + " 创建一次催发货动作。", fingerprint,
                0, AgentWorkflowCheckpointStatusEnum.OPEN, null, now, null);
    }

    private AgentQuestionCardModel orderQuestion(AgentWorkflowRunModel run, AgentTurnModel turn,
                                                 List<OrderSnapshotModel> candidates, Instant now) {
        List<String> options = candidates.stream().limit(3).map(OrderSnapshotModel::orderId).toList();
        AgentQuestionFieldModel orderField = new AgentQuestionFieldModel("orderId", true, 128, options, true);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("step", "ORDER_SELECT");
        summary.put("stepNo", 1);
        summary.put("summary", List.of(Map.of("label", "操作", "value", "催发货")));
        summary.put("fields", List.of(Map.of("name", "orderId", "required", true,
                "maxLength", 128, "options", options, "allowCustom", true)));
        return AgentQuestionCardModel.workflow("question-" + UUID.randomUUID(), run.runId(),
                run.threadId(), turn.turnId(), run.userId(), 1, "请确认具体订单",
                options.isEmpty() ? "暂未找到可催发货的已支付订单，请补充订单号。"
                        : "请选择要催发货的订单；如果列表中没有，请填写订单号。",
                json(summary), List.of(orderField), now);
    }

    private void appendOrderFacts(AgentThreadModel thread, AgentTurnModel turn,
                                  List<OrderSnapshotModel> candidates, OrderSnapshotModel selected, Instant now) {
        if (turn == null) return;
        if (candidates != null && !candidates.isEmpty()) {
            append(thread, turn, AgentItemTypeEnum.ORDER_LIST, Map.of("status", "SUCCESS", "orders",
                    candidates.stream().limit(MAX_CANDIDATES).map(this::safeOrder).toList()),
                    AgentTurnItemPayloads.orderListValue(candidates.stream().limit(MAX_CANDIDATES).toList()), now);
        }
        if (selected != null) {
            append(thread, turn, AgentItemTypeEnum.ORDER_DETAIL, safeOrder(selected),
                    AgentTurnItemPayloads.orderDetailValue(selected), now);
        }
    }

    private void appendSteps(AgentThreadModel thread, AgentTurnModel turn, String runId,
                             String activeNode, String activeStatus, Instant now) {
        // Java Run 的节点固定在此版本，不读取图节点或技术快照。
        List<String> nodes = List.of(RESOLVE_ORDER, "VERIFY_FACTS", "PREPARE_CONFIRMATION", AUTHORIZE,
                REVERIFY_FACTS, BUILD_ACTION_COMMAND, HANDOFF_WORKER, "VERIFY_OUTCOME");
        int active = nodes.indexOf(activeNode);
        for (int index = 0; index < nodes.size(); index++) {
            String status = index < active ? "COMPLETED" : index == active ? activeStatus : "PENDING";
            appendStep(thread, turn, runId, nodes.get(index), status, nodes.get(index), now);
        }
    }

    private void appendStep(AgentThreadModel thread, AgentTurnModel turn, String runId,
                            String node, String status, String branch, Instant now) {
        append(thread, turn, AgentItemTypeEnum.WORKFLOW_STEP,
                AgentTurnItemPayloads.workflowStep(runId, node, status, branch, null, 0),
                AgentTurnItemPayloads.workflowStepValue(runId, node, status, branch, null, 0), now);
    }

    private void appendResult(AgentThreadModel thread, AgentTurnModel turn, String runId,
                              String status, String message, Instant now) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("runId", runId);
        value.put("status", status);
        if (message != null && !message.isBlank()) value.put("message", message);
        append(thread, turn, AgentItemTypeEnum.WORKFLOW_RESULT, value,
                AgentTurnItemPayloads.workflowResultValue(runId, status, message), now);
    }

    private void append(AgentThreadModel thread, AgentTurnModel turn, AgentItemTypeEnum type,
                        Object payload, Instant now) {
        append(thread, turn, type, payload, null, now);
    }

    private void append(AgentThreadModel thread, AgentTurnModel turn, AgentItemTypeEnum type,
                        Object payload, Object structured, Instant now) {
        String encoded = structured instanceof cn.ethan.core.agent.thread.AgentItemPayloadValue value
                && payloadCodec != null ? payloadCodec.encode(type, value)
                : payloadCodec == null ? payload instanceof String raw ? raw : json(payload)
                : payloadCodec.encodeJsonText(type, json(payload));
        AgentItemModel draft = new AgentItemModel(UUID.randomUUID().toString(), thread.threadId(),
                turn.turnId(), 0, type, encoded, now);
        if (journal != null) {
            journal.append(draft);
            return;
        }
        if (items == null) return;
        long sequence = items.appendItem(draft);
        AgentItemModel persisted = new AgentItemModel(draft.itemId(), draft.threadId(), draft.turnId(),
                sequence, draft.type(), draft.payload(), draft.createdAt());
        afterCommit(() -> {
            if (events != null) events.itemCreated(persisted);
        });
    }

    private void projectOwner(AgentThreadModel thread, AgentWorkflowRunModel run,
                              AgentTurnStatusEnum status, String message, Instant now) {
        if (turns == null) return;
        AgentTurnModel owner = turns.findTurn(thread.userId(), run.turnId()).orElse(null);
        if (owner == null || isTerminal(owner.status())) return;
        AgentTurnModel next = status == AgentTurnStatusEnum.WAITING_USER_INPUT
                || status == AgentTurnStatusEnum.WAITING_EXTERNAL_ACTION
                ? owner.workflow(run.runId(), status)
                : owner.terminal(status, status == AgentTurnStatusEnum.FAILED ? "WORKFLOW_FAILED" : null, now);
        if (!turns.updateTurn(owner, next)) {
            throw conflict("TURN_VERSION_CONFLICT", "Workflow owner Turn 版本竞争");
        }
        if (message != null && !message.isBlank()) {
            appendResult(thread, next, run.runId(), status.name(), message, now);
        }
        append(thread, next, AgentItemTypeEnum.TURN_STATE,
                AgentTurnItemPayloads.turnState(status, null), AgentTurnItemPayloads.turnStateValue(status, null), now);
    }

    private boolean isTerminal(AgentTurnStatusEnum status) {
        return status == AgentTurnStatusEnum.COMPLETED || status == AgentTurnStatusEnum.FAILED
                || status == AgentTurnStatusEnum.CANCELLED || status == AgentTurnStatusEnum.TIMED_OUT;
    }

    private OrderSnapshotModel lookup(String orderId, String userId) {
        OrderLookupResultModel result = orders.findOrder(orderId, userId);
        if (result == null || result.status() == OrderLookupStatusEnum.TEMPORARY_FAILURE) {
            throw conflict("ORDER_TEMPORARY_FAILURE", "订单信息暂时无法核验，请稍后重试");
        }
        if (result.status() == OrderLookupStatusEnum.ACCESS_DENIED) {
            throw conflict("ORDER_NOT_OWNED", "订单不属于当前用户");
        }
        if (result.status() != OrderLookupStatusEnum.FOUND || result.order() == null) {
            throw conflict("ORDER_NOT_FOUND", "订单不存在或已不可见");
        }
        if (!userId.equals(result.order().userId())) {
            throw conflict("ORDER_NOT_OWNED", "订单不属于当前用户");
        }
        return result.order();
    }

    private void requireEligible(OrderSnapshotModel order, String userId) {
        if (!policy.eligible(order, userId)) {
            throw conflict("EXPEDITE_ORDER_STATE_INVALID", "仅已支付订单允许催发货");
        }
    }

    private void requireQuestionIdentity(AgentThreadModel thread, AgentTurnModel turn,
                                         AgentQuestionAnswerInput input, AgentQuestionCardModel question) {
        if (question.resumeTarget() != cn.ethan.core.agent.workflow.AgentQuestionCardResumeTargetEnum.WORKFLOW
                || !question.threadId().equals(thread.threadId()) || !java.util.Objects.equals(question.runId(), input.runId())
                || question.version() != input.enqueuedQuestionVersion()
                || !turn.turnId().equals(question.answerTurnId())) {
            throw conflict("QUESTION_VERSION_CONFLICT", "QuestionCard 回答版本已变化");
        }
    }

    private void requireNoOpenInteraction(String userId, String threadId) {
        if (questions.findOpen(userId, threadId).isPresent() || checkpoints.findOpen(userId, threadId).isPresent()) {
            throw conflict("THREAD_WORKFLOW_ACTIVE", "当前 Thread 已有开放交互");
        }
    }

    private StartResult existingStart(AgentWorkflowRunModel run) {
        AgentQuestionCardModel question = questions.findOpenByRun(run.userId(), run.runId()).orElse(null);
        AgentWorkflowCheckpointModel checkpoint = checkpoints.findOpenByRun(run.userId(), run.runId()).orElse(null);
        return new StartResult(run.runId(), question, checkpoint);
    }

    private Request requestFrom(AgentWorkflowRunModel run) {
        try {
            JsonNode root = objectMapper.readTree(run.stateJson());
            return new Request(root.path("intent").asString(INTENT), root.path("requestOrderId").asString(""),
                    root.path("reason").asString(""), root.path("orderId").asString(""),
                    stringMap(root.path("criteria")));
        } catch (RuntimeException failure) {
            throw new IllegalStateException("无法恢复 Java 催发货 Workflow 状态", failure);
        }
    }

    private void requireSameRequest(Request request, AgentWorkflowRunModel run) {
        Request stored = requestFrom(run);
        if (!request.intent().equals(stored.intent()) || !request.requestOrderId().equals(stored.requestOrderId())
                || !request.reason().equals(stored.reason()) || !request.criteria().equals(stored.criteria())) {
            throw conflict("WORKFLOW_SOURCE_CONFLICT", "同一来源 Turn 已启动不同的订单 Workflow");
        }
    }

    private void requireJavaRun(AgentWorkflowRunModel run) {
        if (run.orchestrationVersion() != AgentWorkflowOrchestrationVersionEnum.EXPEDITE_JAVA_V1) {
            throw conflict("UNKNOWN_WORKFLOW_ORCHESTRATION_VERSION", "该 WorkflowRun 不属于 Java 催发货版本");
        }
    }

    private String snapshot(String activeNode, String status) {
        return OrderWorkflowStepProjection.snapshot(objectMapper, activeNode, status,
                AgentWorkflowOrchestrationVersionEnum.EXPEDITE_JAVA_V1);
    }

    private Map<String, Object> state(Request request, OrderSnapshotModel order, String fingerprint) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("intent", INTENT);
        value.put("requestOrderId", request.requestOrderId());
        value.put("orderId", order == null ? "" : order.orderId());
        value.put("reason", request.reason());
        value.put("criteria", request.criteriaValues());
        value.put("factsFingerprint", fingerprint);
        return value;
    }

    private Map<String, Object> safeOrder(OrderSnapshotModel order) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("orderId", order.orderId());
        value.put("status", order.status().name());
        if (order.createdAt() != null) value.put("createdAt", order.createdAt().toString());
        if (order.expectedDeliveryAt() != null) value.put("expectedDeliveryAt", order.expectedDeliveryAt().toString());
        if (order.lastLogisticsAt() != null) value.put("lastLogisticsAt", order.lastLogisticsAt().toString());
        if (order.logisticsStatus() != null) value.put("logisticsStatus", order.logisticsStatus());
        if (order.paidAmount() != null) value.put("paidAmount", order.paidAmount().toPlainString());
        if (order.currency() != null) value.put("currency", order.currency());
        if (order.itemSummary() != null) value.put("itemSummary", order.itemSummary());
        value.put("visibility", order.hiddenAt() == null ? "ACTIVE" : "HIDDEN");
        return value;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception failure) {
            throw new IllegalStateException("无法编码 Java 催发货 Workflow 数据", failure);
        }
    }

    private Map<String, String> stringMap(JsonNode value) {
        Map<String, String> result = new LinkedHashMap<>();
        if (value != null && value.isObject()) {
            value.properties().forEach(entry -> result.put(entry.getKey(), entry.getValue().asString("")));
        }
        return Map.copyOf(result);
    }

    private static Instant parseBoundary(String name, String value, boolean endOfDay) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value.strip());
        } catch (DateTimeParseException instantFailure) {
            try {
                return LocalDate.parse(value.strip()).atTime(endOfDay ? LocalTime.MAX : LocalTime.MIN)
                        .toInstant(ZoneOffset.UTC);
            } catch (DateTimeParseException dateFailure) {
                throw new IllegalArgumentException("订单 Workflow 日期参数无效：" + name);
            }
        }
    }

    private static BigDecimal parseAmount(String name, String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return new BigDecimal(value.strip());
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("订单 Workflow 金额参数无效：" + name);
        }
    }

    private void appendItem(AgentThreadModel thread, AgentTurnModel turn, AgentItemTypeEnum type,
                            String encodedPayload, Instant now) {
        AgentItemModel draft = new AgentItemModel(UUID.randomUUID().toString(), thread.threadId(), turn.turnId(),
                0, type, encodedPayload, now);
        if (journal != null) {
            journal.append(draft);
        } else if (items != null) {
            long sequence = items.appendItem(draft);
            AgentItemModel persisted = new AgentItemModel(draft.itemId(), draft.threadId(), draft.turnId(), sequence,
                    draft.type(), draft.payload(), draft.createdAt());
            afterCommit(() -> { if (events != null) events.itemCreated(persisted); });
        }
    }

    private void afterCommit(Runnable callback) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() { callback.run(); }
            });
        } else {
            callback.run();
        }
    }

    private <T> T inTransaction(Supplier<T> work) {
        if (transactions == null) return work.get();
        T value = transactions.execute((TransactionStatus status) -> work.get());
        if (value == null) throw new IllegalStateException("Workflow 事务未返回结果");
        return value;
    }

    private boolean isSourceUniquenessFailure(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message == null) continue;
            String normalized = message.toUpperCase(java.util.Locale.ROOT);
            if (normalized.contains("UQ_AGENT_WORKFLOW_RUN_SOURCE")
                    || ((normalized.contains("DUPLICATE") || normalized.contains("UNIQUE"))
                    && normalized.contains("TURN_ID") && normalized.contains("WORKFLOW_TYPE"))) return true;
        }
        return false;
    }

    private String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) throw conflict("QUESTION_ANSWER_INVALID", "缺少回答字段：" + key);
        return value.strip();
    }

    private AgentThreadConflictException conflict(String code, String message) {
        return new AgentThreadConflictException(code, message);
    }

    private record Resolved(List<OrderSnapshotModel> candidates, OrderSnapshotModel order) {
        private Resolved {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }
    }

    private record Request(String intent, String requestOrderId, String reason, String selectedOrderId,
                           Map<String, String> criteriaValues) {
        private static Request from(String operation, Map<String, String> arguments) {
            if (!"ORDER_SERVICE".equalsIgnoreCase(operation == null ? "" : operation.strip())) {
                throw new AgentThreadConflictException("WORKFLOW_INTENT_INVALID", "只支持订单售后 Workflow");
            }
            Map<String, String> safe = arguments == null ? Map.of() : arguments;
            String suppliedIntent = normalize(safe.get("intent"));
            String intent = (suppliedIntent.isBlank() ? normalize(operation) : suppliedIntent)
                    .toUpperCase(Locale.ROOT);
            if (!INTENT.equals(intent)) {
                throw new AgentThreadConflictException("WORKFLOW_INTENT_INVALID", "Java Workflow 仅支持催发货");
            }
            String orderId = normalize(safe.get("orderId"));
            String reason = normalize(safe.get("reason"));
            if (orderId.length() > 128 || reason.length() > 512) {
                throw new AgentThreadConflictException("WORKFLOW_ARGUMENT_INVALID", "订单参数超过长度限制");
            }
            Map<String, String> criteria = new LinkedHashMap<>();
            for (String key : List.of("createdFrom", "createdTo", "minAmount", "maxAmount", "statuses",
                    "keyword", "logisticsStalledDays", "visibility")) {
                String value = normalize(safe.get(key));
                if (!value.isBlank()) criteria.put(key, value);
            }
            return new Request(intent, orderId, reason, orderId, Map.copyOf(criteria));
        }

        private Request withResolvedOrder(String orderId) {
            return new Request(intent, requestOrderId, reason, orderId, criteriaValues);
        }

        private OrderSearchCriteria criteria() {
            String stalled = criteriaValues.getOrDefault("logisticsStalledDays", "");
            Set<OrderStatusEnum> statuses = criteriaValues.getOrDefault("statuses", "").isBlank() ? Set.of()
                    : Arrays.stream(criteriaValues.get("statuses").split(","))
                    .map(String::strip).filter(value -> !value.isBlank())
                    .map(value -> OrderStatusEnum.valueOf(value.toUpperCase(Locale.ROOT)))
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            String visibilityValue = criteriaValues.getOrDefault("visibility", "ACTIVE");
            return new OrderSearchCriteria(parseBoundary("createdFrom", criteriaValues.get("createdFrom"), false),
                    parseBoundary("createdTo", criteriaValues.get("createdTo"), true),
                    parseAmount("minAmount", criteriaValues.get("minAmount")),
                    parseAmount("maxAmount", criteriaValues.get("maxAmount")), statuses,
                    criteriaValues.get("keyword"), stalled.isBlank() ? null : Integer.valueOf(stalled),
                    cn.ethan.core.commerce.order.OrderVisibilityEnum.valueOf(
                            visibilityValue.toUpperCase(Locale.ROOT)), MAX_CANDIDATES);
        }

        private static String normalize(String value) {
            return value == null ? "" : value.strip();
        }
    }
}
