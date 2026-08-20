package cn.ethan.infrastructure.agent.workflow.transaction;

import cn.ethan.core.agent.action.ExternalActionStatusEnum;
import cn.ethan.core.agent.action.ExternalActionTypeEnum;
import cn.ethan.core.agent.action.ExternalActionCommandModel;
import cn.ethan.core.agent.workflow.AgentWorkflowQuestionModel;
import cn.ethan.core.agent.thread.AgentThreadModel;
import cn.ethan.core.agent.thread.AgentTurnModel;
import cn.ethan.core.agent.workflow.AgentWorkflowRunModel;
import cn.ethan.core.agent.workflow.AgentWorkflowEngine;
import cn.ethan.core.agent.workflow.AgentWorkflowQuestionStore;
import cn.ethan.core.agent.workflow.AgentWorkflowRunStore;
import cn.ethan.core.agent.workflow.AgentWorkflowStatusEnum;
import cn.ethan.core.agent.workflow.AgentWorkflowTypeEnum;
import cn.ethan.core.agent.workflow.AgentWorkflowQuestionStatusEnum;
import cn.ethan.core.agent.thread.AgentThreadConflictException;
import cn.ethan.core.commerce.order.OrderGateway;
import cn.ethan.core.commerce.order.OrderLookupStatusEnum;
import cn.ethan.core.commerce.order.OrderStatusEnum;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

    public TransactionalAgentWorkflowEngine(
            Clock clock,
            AgentWorkflowQuestionStore questions,
            cn.ethan.core.agent.action.ExternalActionCommandStore commands,
            ObjectMapper objectMapper,
            AgentWorkflowRunStore workflowRuns,
            OrderGateway orders
    ) {
        this.clock = clock;
        this.questions = questions;
        this.commands = commands;
        this.objectMapper = objectMapper;
        this.workflowRuns = workflowRuns;
        this.orders = orders;
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public StartResult start(
            AgentThreadModel thread,
            AgentTurnModel turn,
            String operation,
            Map<String, String> arguments
    ) {
        AgentWorkflowTypeEnum workflowType;
        try {
            workflowType = AgentWorkflowTypeEnum.valueOf(operation == null ? "" : operation.trim().toUpperCase());
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("不支持的 Workflow 操作：" + operation);
        }
        String normalized = workflowType.name();
        validateOrder(workflowType, arguments, thread.userId());
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
        String fields;
        try {
            fields = objectMapper.writeValueAsString(Map.of(
                    "operation", normalized,
                    "arguments", arguments == null ? Map.of() : arguments,
                    "fields", List.of(Map.of(
                            "name", "decision", "label", "决定", "type", "CONFIRM",
                            "required", true, "options", List.of("APPROVE", "REJECT")
                    ))
            ));
        } catch (Exception failure) {
            throw new IllegalStateException("无法生成 QuestionCard 字段", failure);
        }
        AgentWorkflowQuestionModel question = new AgentWorkflowQuestionModel(
                runId, thread.threadId(), turn.turnId(), thread.userId(), questionId,
                checkpointId, 0L, title, prompt, fields, AgentWorkflowQuestionStatusEnum.OPEN, now, null
        );
        workflowRuns.create(new AgentWorkflowRunModel(
                runId, thread.threadId(), turn.turnId(), thread.userId(), workflowType,
                AgentWorkflowStatusEnum.WAITING_USER_INPUT, 0L, now, now));
        questions.saveQuestion(question);
        return new StartResult(runId, question);
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
    @org.springframework.transaction.annotation.Transactional
    public ResumeResult resume(AgentThreadModel thread, AgentTurnModel turn, Map<String, String> answers) {
        AgentWorkflowQuestionModel question = questions.findOpenQuestionByRun(thread.userId(), turn.workflowRunId())
                .orElseThrow(() -> new IllegalStateException("Workflow QuestionCard 不存在或已处理"));
        AgentWorkflowRunModel workflowRun = workflowRuns.find(thread.userId(), turn.workflowRunId())
                .orElseThrow(() -> new IllegalStateException("WorkflowRun 不存在或不属于当前用户"));
        String decision = answers == null ? "" : answers.getOrDefault("decision", "");
        AgentWorkflowQuestionModel answered = question.answered(clock.instant());
        questions.answerQuestion(answered);
        if (!(decision.equalsIgnoreCase("APPROVE") || decision.equalsIgnoreCase("CONFIRM") || decision.equals("同意"))) {
            workflowRuns.update(workflowRun.status(AgentWorkflowStatusEnum.REJECTED, clock.instant()));
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
            return new ResumeResult("已确认，外部动作已进入可靠执行队列。", "APPROVED", existing);
        } catch (Exception failure) {
            throw new IllegalStateException("无法创建外部动作命令", failure);
        }
    }
}
