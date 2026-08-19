package cn.ethan.core.agent.thread.port;

import cn.ethan.core.agent.thread.model.AgentItemModel;
import cn.ethan.core.agent.thread.model.AgentQuestionModel;
import cn.ethan.core.agent.thread.model.AgentThreadModel;
import cn.ethan.core.agent.thread.model.AgentTurnModel;

import java.util.List;
import java.util.Map;

/**
 * 类型职责：定义协调 Agent 调用模型、工具和 Workflow 的 Core 端口。
 *
 * @author ethan
 * @date 2026-08-19
 */
public interface AgentCoordinatorProvider {

    AgentCoordinatorResult run(
            AgentThreadModel thread,
            AgentTurnModel turn,
            List<AgentItemModel> context,
            Map<String, String> answer
    );

    record AgentCoordinatorResult(
            String assistantMessage,
            List<AgentItemDraft> items,
            AgentQuestionModel question,
            String workflowRunId,
            boolean waitingUserInput
    ) {
        public AgentCoordinatorResult {
            assistantMessage = assistantMessage == null ? "" : assistantMessage;
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    record AgentItemDraft(String type, String payload) {
    }
}
