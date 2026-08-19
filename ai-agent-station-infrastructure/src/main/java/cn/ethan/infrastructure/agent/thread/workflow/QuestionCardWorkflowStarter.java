package cn.ethan.infrastructure.agent.thread.workflow;

import cn.ethan.core.agent.thread.model.AgentQuestionModel;
import cn.ethan.core.agent.thread.model.AgentThreadModel;
import cn.ethan.core.agent.thread.model.AgentTurnModel;
import cn.ethan.core.agent.thread.port.AgentWorkflowStarter;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
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

    public QuestionCardWorkflowStarter(Clock clock) {
        this.clock = clock;
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
        String fields = "[{\"name\":\"decision\",\"label\":\"决定\","
                + "\"type\":\"CONFIRM\",\"required\":true,"
                + "\"options\":[\"APPROVE\",\"REJECT\"]}]";
        AgentQuestionModel question = new AgentQuestionModel(
                runId, thread.threadId(), turn.turnId(), thread.userId(), questionId,
                checkpointId, 0L, title, prompt, fields, "OPEN", now, null
        );
        return new StartResult(runId, question);
    }
}
