package cn.ethan.core.agent.thread.port;

import cn.ethan.core.agent.thread.model.AgentQuestionModel;
import cn.ethan.core.agent.thread.model.AgentThreadModel;
import cn.ethan.core.agent.thread.model.AgentTurnModel;
import cn.ethan.core.agent.action.model.ExternalActionCommandModel;

import java.util.Map;

/**
 * 类型职责：定义协调 Agent 启动确定性 Workflow 的最小端口。
 *
 * @author ethan
 * @date 2026-08-19
 */
public interface AgentWorkflowStarter {

    StartResult start(
            AgentThreadModel thread,
            AgentTurnModel turn,
            String operation,
            Map<String, String> arguments
    );

    ResumeResult resume(AgentThreadModel thread, AgentTurnModel turn, Map<String, String> answers);

    record StartResult(String runId, AgentQuestionModel question) {
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
