package cn.ethan.infrastructure.agent.thread.workflow;

import cn.ethan.core.agent.action.enums.ExternalActionStatusEnum;
import cn.ethan.core.agent.action.enums.ExternalActionTypeEnum;
import cn.ethan.core.agent.action.model.ExternalActionCommandModel;
import cn.ethan.core.agent.thread.model.AgentQuestionModel;
import cn.ethan.core.agent.thread.model.AgentThreadModel;
import cn.ethan.core.agent.thread.model.AgentTurnModel;
import cn.ethan.core.agent.thread.model.AgentWorkflowRunModel;
import cn.ethan.core.agent.thread.port.AgentWorkflowStarter;
import cn.ethan.core.agent.thread.port.AgentThreadStore;
import cn.ethan.core.agent.thread.port.AgentWorkflowRunStore;
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
public final class QuestionCardWorkflowStarter implements AgentWorkflowStarter {

    private final Clock clock;
    private final AgentThreadStore threads;
    private final cn.ethan.core.agent.action.port.ExternalActionCommandStore commands;
    private final ObjectMapper objectMapper;
    private final AgentWorkflowRunStore workflowRuns;

    public QuestionCardWorkflowStarter(
            Clock clock,
            AgentThreadStore threads,
            cn.ethan.core.agent.action.port.ExternalActionCommandStore commands,
            ObjectMapper objectMapper,
            AgentWorkflowRunStore workflowRuns
    ) {
        this.clock = clock;
        this.threads = threads;
        this.commands = commands;
        this.objectMapper = objectMapper;
        this.workflowRuns = workflowRuns;
    }

    @Override
    public StartResult start(
            AgentThreadModel thread,
            AgentTurnModel turn,
            String operation,
            Map<String, String> arguments
    ) {
        String normalized = operation == null ? "" : operation.trim().toUpperCase();
        if (!normalized.equals("REFUND") && !normalized.equals("EXPEDITE")) {
            throw new IllegalArgumentException("不支持的 Workflow 操作：" + operation);
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
        AgentQuestionModel question = new AgentQuestionModel(
                runId, thread.threadId(), turn.turnId(), thread.userId(), questionId,
                checkpointId, 0L, title, prompt, fields, "OPEN", now, null
        );
        workflowRuns.create(new AgentWorkflowRunModel(
                runId, thread.threadId(), turn.turnId(), thread.userId(), normalized,
                "WAITING_USER_INPUT", 0L, now, now));
        return new StartResult(runId, question);
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public ResumeResult resume(AgentThreadModel thread, AgentTurnModel turn, Map<String, String> answers) {
        AgentQuestionModel question = threads.findOpenQuestionByRun(thread.userId(), turn.workflowRunId())
                .orElseThrow(() -> new IllegalStateException("Workflow QuestionCard 不存在或已处理"));
        AgentWorkflowRunModel workflowRun = workflowRuns.find(thread.userId(), turn.workflowRunId())
                .orElseThrow(() -> new IllegalStateException("WorkflowRun 不存在或不属于当前用户"));
        String decision = answers == null ? "" : answers.getOrDefault("decision", "");
        AgentQuestionModel answered = question.answered(clock.instant());
        threads.answerQuestion(answered);
        if (!(decision.equalsIgnoreCase("APPROVE") || decision.equalsIgnoreCase("CONFIRM") || decision.equals("同意"))) {
            workflowRuns.update(workflowRun.status("COMPLETED", clock.instant()));
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
            workflowRuns.update(workflowRun.status("WAITING_EXTERNAL_ACTION", clock.instant()));
            return new ResumeResult("已确认，外部动作已进入可靠执行队列。", "APPROVED", existing);
        } catch (Exception failure) {
            throw new IllegalStateException("无法创建外部动作命令", failure);
        }
    }
}
