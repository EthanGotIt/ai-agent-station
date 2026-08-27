package cn.ethan.core.agent.workflow;

import cn.ethan.core.agent.thread.AgentThreadModel;
import cn.ethan.core.agent.thread.AgentTurnModel;
import cn.ethan.core.agent.action.ExternalActionCommandModel;

import java.util.Map;

/**
 * 类型职责：定义协调 Agent 启动确定性 Workflow 的最小端口。
 *
 * @author ethan
 * @date 2026-08-19
 */
public interface AgentWorkflowEngine {

    StartResult start(
            AgentThreadModel thread,
            AgentTurnModel turn,
            String operation,
            Map<String, String> arguments
    );

    ResumeResult resume(AgentThreadModel thread, AgentTurnModel turn, Map<String, String> answers);

    /** Workflow 启动结果：固定流程只能返回独立 QuestionCard 或执行 Checkpoint。 */
    record StartResult(
            String runId,
            AgentQuestionCardModel questionCard,
            AgentWorkflowCheckpointModel checkpoint
    ) {
    }

    record ResumeResult(
            String message,
            String resultStatus,
            ExternalActionCommandModel command,
            AgentQuestionCardModel questionCard,
            AgentWorkflowCheckpointModel checkpoint
    ) {
        public ResumeResult(String message, String resultStatus, ExternalActionCommandModel command) {
            this(message, resultStatus, command, null, null);
        }

        public ResumeResult {
            message = message == null ? "" : message;
            resultStatus = resultStatus == null ? "" : resultStatus;
        }
    }
}
