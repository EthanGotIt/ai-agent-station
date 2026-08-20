package cn.ethan.core.agent.coordination;

import cn.ethan.core.agent.thread.AgentItemModel;
import cn.ethan.core.agent.workflow.AgentWorkflowQuestionModel;
import cn.ethan.core.agent.thread.AgentThreadModel;
import cn.ethan.core.agent.thread.AgentTurnModel;
import cn.ethan.core.agent.execution.AgentExecutionContext;

import java.util.List;
import java.util.Map;

/**
 * 类型职责：定义协调 Agent 调用模型、工具和 Workflow 的 Core 端口。
 *
 * @author ethan
 * @date 2026-08-19
 */
public interface AgentTurnCoordinator {

    AgentCoordinatorResult run(
            AgentThreadModel thread,
            AgentTurnModel turn,
            List<AgentItemModel> context,
            Map<String, String> answer
    );

    default AgentCoordinatorResult run(
            AgentThreadModel thread,
            AgentTurnModel turn,
            List<AgentItemModel> context,
            Map<String, String> answer,
            AgentExecutionContext executionContext
    ) {
        executionContext.checkActive();
        AgentCoordinatorResult result = run(thread, turn, context, answer);
        executionContext.checkActive();
        return result;
    }

    record AgentCoordinatorResult(
            String assistantMessage,
            List<AgentItemDraft> items,
            AgentWorkflowQuestionModel question,
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
