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

    record StartResult(String runId, AgentWorkflowQuestionModel question) {
    }

    record ResumeResult(
            String message,
            String resultStatus,
            ExternalActionCommandModel command
    ) {
        public ResumeResult {
            message = message == null ? "" : message;
            resultStatus = resultStatus == null ? "" : resultStatus;
        }
    }
}
