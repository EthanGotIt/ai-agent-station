package cn.ethan.infrastructure.agent.workflow.transaction;

import cn.ethan.core.agent.action.ExternalActionStatusEnum;
import cn.ethan.core.agent.action.ExternalActionTypeEnum;
import cn.ethan.core.agent.action.ExternalActionCommandModel;
import cn.ethan.core.agent.workflow.AgentWorkflowQuestionModel;
import cn.ethan.core.agent.workflow.AgentWorkflowQuestionFieldModel;
import cn.ethan.core.agent.thread.AgentThreadModel;
import cn.ethan.core.agent.thread.AgentTurnModel;
import cn.ethan.core.agent.thread.AgentWorkflowAnswerInput;
import cn.ethan.core.agent.workflow.AgentWorkflowRunModel;
import cn.ethan.core.agent.workflow.AgentWorkflowEngine;
import cn.ethan.core.agent.workflow.AgentWorkflowQuestionStore;
import cn.ethan.core.agent.workflow.AgentWorkflowRunStore;
import cn.ethan.core.agent.workflow.AgentWorkflowStatusEnum;
import cn.ethan.core.agent.workflow.AgentWorkflowTypeEnum;
import cn.ethan.core.agent.workflow.AgentWorkflowQuestionStatusEnum;
import cn.ethan.core.agent.workflow.AgentWorkflowDecisionEnum;
import cn.ethan.core.agent.thread.AgentThreadConflictException;
import cn.ethan.core.agent.thread.AgentItemModel;
import cn.ethan.core.agent.thread.AgentItemStore;
import cn.ethan.core.agent.thread.AgentItemTypeEnum;
import cn.ethan.core.commerce.order.OrderGateway;
import cn.ethan.core.commerce.order.OrderLookupStatusEnum;
import cn.ethan.core.commerce.order.OrderStatusEnum;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 类型职责：为演示 Workflow 生成持久化 QuestionCard，暂不执行外部副作用。
 *
 * @author ethan
 * @date 2026-08-19
 */
@Component
public final class TransactionalAgentWorkflowEngine implements AgentWorkflowEngine {

    private final Clock clock;
    private final AgentWorkflowQuestionStore questions;
    private final cn.ethan.core.agent.action.ExternalActionCommandStore commands;
    private final ObjectMapper objectMapper;
    private final AgentWorkflowRunStore workflowRuns;
    private final OrderGateway orders;
    private final AgentItemStore items;
    private final TransactionTemplate transactionTemplate;

    /**
     * 测试替身构造器：没有事务管理器时仍可验证纯领域状态转换。
     */
    public TransactionalAgentWorkflowEngine(
            Clock clock,
            AgentWorkflowQuestionStore questions,
            cn.ethan.core.agent.action.ExternalActionCommandStore commands,
            ObjectMapper objectMapper,
            AgentWorkflowRunStore workflowRuns,
            OrderGateway orders,
            AgentItemStore items
    ) {
        this(clock, questions, commands, objectMapper, workflowRuns, orders, items, null);
    }

    @Autowired
    public TransactionalAgentWorkflowEngine(
            Clock clock,
            AgentWorkflowQuestionStore questions,
            cn.ethan.core.agent.action.ExternalActionCommandStore commands,
            ObjectMapper objectMapper,
            AgentWorkflowRunStore workflowRuns,
            OrderGateway orders,
            AgentItemStore items,
            PlatformTransactionManager transactionManager
    ) {
        this.clock = clock;
        this.questions = questions;
        this.commands = commands;
        this.objectMapper = objectMapper;
        this.workflowRuns = workflowRuns;
        this.orders = orders;
        this.items = items;
        this.transactionTemplate = transactionManager == null ? null : new TransactionTemplate(transactionManager);
    }

    @Override
    public StartResult start(
            AgentThreadModel thread,
            AgentTurnModel turn,
            String operation,
            Map<String, String> arguments
    ) {
        AgentWorkflowTypeEnum workflowType = parseWorkflowType(operation);
        validateOrder(workflowType, arguments, thread.userId());
        return inTransaction(() -> persistStart(thread, turn, workflowType, arguments));
    }

    private StartResult persistStart(
            AgentThreadModel thread,
            AgentTurnModel turn,
            AgentWorkflowTypeEnum workflowType,
            Map<String, String> arguments
    ) {
        String normalized = workflowType.name();
        var openQuestion = questions.findOpenQuestion(thread.userId(), thread.threadId());
        if (openQuestion.isPresent()) {
            AgentWorkflowRunModel existingRun = workflowRuns.find(thread.userId(), openQuestion.get().runId())
                    .orElseThrow(() -> new IllegalStateException("开放 QuestionCard 缺少 WorkflowRun"));
            if (existingRun.workflowType() == workflowType) {
                return new StartResult(existingRun.runId(), openQuestion.get());
            }
            throw new AgentThreadConflictException("THREAD_WORKFLOW_ACTIVE", "当前 Thread 已有其他 Workflow 等待确认");
        }
        String runId = "workflow-" + UUID.randomUUID();
        String questionId = "question-" + UUID.randomUUID();
        String checkpointId = "checkpoint-" + normalized.toLowerCase();
        Instant now = clock.instant();
        String title = normalized.equals("REFUND") ? "退款确认" : "催发货确认";
        String prompt = normalized.equals("REFUND")
                ? "退款属于外部写操作，请确认是否提交退款命令。"
                : "催发货会向外部履约系统提交动作，请确认是否继续。";
        List<AgentWorkflowQuestionFieldModel> answerFields = List.of(
                new AgentWorkflowQuestionFieldModel(
                        "decision", true, 32, List.of("APPROVE", "REJECT")));
        String fields;
        try {
            fields = objectMapper.writeValueAsString(Map.of(
                    "operation", normalized,
                    "arguments", arguments == null ? Map.of() : arguments,
                    "fields", List.of(Map.of(
                            "name", "decision", "label", "决定", "type", "CONFIRM",
                            "required", true, "maxLength", 32,
                            "options", List.of("APPROVE", "REJECT")
                    ))
            ));
        } catch (Exception failure) {
            throw new IllegalStateException("无法生成 QuestionCard 字段", failure);
        }
        AgentWorkflowQuestionModel question = new AgentWorkflowQuestionModel(
                runId, thread.threadId(), turn.turnId(), thread.userId(), questionId,
                checkpointId, 0L, title, prompt, fields, AgentWorkflowQuestionStatusEnum.OPEN, now, null,
                null, AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum.AVAILABLE, answerFields
        );
        workflowRuns.create(new AgentWorkflowRunModel(
                runId, thread.threadId(), turn.turnId(), thread.userId(), workflowType,
                AgentWorkflowStatusEnum.WAITING_USER_INPUT, 0L, now, now));
        questions.saveQuestion(question);
        appendItem(thread, turn, AgentItemTypeEnum.WORKFLOW_STARTED, runId, now);
        appendItem(thread, turn, AgentItemTypeEnum.WORKFLOW_QUESTION, questionPayload(question), now);
        return new StartResult(runId, question);
    }

    private AgentWorkflowTypeEnum parseWorkflowType(String operation) {
        try {
            return AgentWorkflowTypeEnum.valueOf(operation == null ? "" : operation.trim().toUpperCase());
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("不支持的 Workflow 操作：" + operation);
        }
    }

    private void validateOrder(AgentWorkflowTypeEnum workflowType, Map<String, String> arguments, String userId) {
        String orderId = arguments == null ? "" : arguments.getOrDefault("orderId", "");
        if (orderId.isBlank()) {
            throw new AgentThreadConflictException("ORDER_REQUIRED", "Workflow 必须指定订单号");
        }
        var lookup = orders.findOrder(orderId, userId);
        if (lookup.status() != OrderLookupStatusEnum.FOUND || lookup.order() == null) {
            throw new AgentThreadConflictException(
                    lookup.status() == OrderLookupStatusEnum.ACCESS_DENIED ? "ORDER_NOT_OWNED" : "ORDER_NOT_FOUND",
                    "订单不存在、无归属或暂时不可用");
        }
        OrderStatusEnum status = lookup.order().status();
        if (workflowType == AgentWorkflowTypeEnum.REFUND
                && !List.of(OrderStatusEnum.PAID, OrderStatusEnum.SHIPPED, OrderStatusEnum.DELIVERED).contains(status)) {
            throw new AgentThreadConflictException("REFUND_ORDER_STATE_INVALID", "当前订单状态不允许退款");
        }
        if (workflowType == AgentWorkflowTypeEnum.EXPEDITE && status != OrderStatusEnum.PAID) {
            throw new AgentThreadConflictException("EXPEDITE_ORDER_STATE_INVALID", "仅已支付订单允许催发货");
        }
    }

    @Override
    public ResumeResult resume(AgentThreadModel thread, AgentTurnModel turn, Map<String, String> answers) {
        return inTransaction(() -> resumeInTransaction(thread, turn, answers));
    }

    private ResumeResult resumeInTransaction(
            AgentThreadModel thread,
            AgentTurnModel turn,
            Map<String, String> answers
    ) {
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
        AgentWorkflowRunModel workflowRun = workflowRuns.find(thread.userId(), answerInput.runId())
                .orElseThrow(() -> new IllegalStateException("WorkflowRun 不存在或不属于当前用户"));
        Map<String, String> validatedAnswers = question.validateAnswers(answerInput.answers());
        String decision = validatedAnswers.getOrDefault("decision", "");
        AgentWorkflowDecisionEnum workflowDecision = parseDecision(decision);
        Instant answeredAt = clock.instant();
        if (!questions.closeAnswerTurn(thread.userId(), question.questionId(),
                answerInput.enqueuedQuestionVersion(),
                turn.turnId(), answeredAt)) {
            throw new AgentThreadConflictException(
                    "WORKFLOW_VERSION_CONFLICT", "QuestionCard 回答 Turn、入队状态或版本已变化");
        }
        if (workflowDecision == AgentWorkflowDecisionEnum.REJECT) {
            workflowRuns.update(workflowRun.status(AgentWorkflowStatusEnum.REJECTED, clock.instant()));
            appendItem(thread, turn, AgentItemTypeEnum.WORKFLOW_RESULT,
                    "{\"status\":\"REJECTED\",\"runId\":\"" + escape(question.runId()) + "\"}", clock.instant());
            return new ResumeResult("已拒绝本次操作，Workflow 已安全结束。", "REJECTED", null);
        }
        try {
            JsonNode root = objectMapper.readTree(question.fieldsJson());
            String operation = root.path("operation").asText();
            ExternalActionTypeEnum type = ExternalActionTypeEnum.valueOf(operation);
            String idempotencyKey = "workflow:" + question.runId() + ":" + type.name();
            String payloadJson = root.path("arguments").toString();
            ExternalActionCommandModel command = new ExternalActionCommandModel(
                    "action-" + java.util.UUID.randomUUID(), question.runId(), thread.threadId(), turn.turnId(),
                    thread.userId(), type, idempotencyKey, payloadJson, ExternalActionStatusEnum.PENDING,
                    0, 3, clock.instant(), null, null, null, null, clock.instant(), clock.instant(), null
            );
            ExternalActionCommandModel existing = commands.createIfAbsent(command);
            workflowRuns.update(workflowRun.status(AgentWorkflowStatusEnum.WAITING_EXTERNAL_ACTION, clock.instant()));
            appendItem(thread, turn, AgentItemTypeEnum.EXTERNAL_ACTION_STATUS,
                    "{\"commandId\":\"" + escape(existing.commandId()) + "\",\"status\":\""
                            + existing.status().name() + "\",\"idempotencyKey\":\""
                            + escape(existing.idempotencyKey()) + "\"}", clock.instant());
            return new ResumeResult("已确认，外部动作已进入可靠执行队列。", "APPROVED", existing);
        } catch (Exception failure) {
            throw new IllegalStateException("无法创建外部动作命令", failure);
        }
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

    private void appendItem(
            AgentThreadModel thread,
            AgentTurnModel turn,
            AgentItemTypeEnum type,
            String payload,
            Instant createdAt
    ) {
        items.appendItem(new AgentItemModel(
                "workflow-" + turn.turnId() + "-" + type.name().toLowerCase() + "-" + UUID.randomUUID(),
                thread.threadId(), turn.turnId(), 0, type, payload, createdAt));
    }

    private AgentWorkflowDecisionEnum parseDecision(String value) {
        if (value == null) return AgentWorkflowDecisionEnum.REJECT;
        String normalized = value.trim();
        if (normalized.equalsIgnoreCase("APPROVE")
                || normalized.equalsIgnoreCase("CONFIRM") || normalized.equals("同意")) {
            return AgentWorkflowDecisionEnum.APPROVE;
        }
        return AgentWorkflowDecisionEnum.REJECT;
    }

    private String questionPayload(AgentWorkflowQuestionModel question) {
        return "{\"runId\":\"" + escape(question.runId()) + "\",\"questionId\":\""
                + escape(question.questionId()) + "\",\"checkpointId\":\"" + escape(question.checkpointId())
                + "\",\"version\":" + question.version() + ",\"title\":\"" + escape(question.title())
                + "\",\"prompt\":\"" + escape(question.prompt()) + "\",\"fields\":"
                + question.fieldsJson() + "}";
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }
}
