package cn.ethan.core.agent.coordination;

import cn.ethan.core.agent.thread.AgentItemModel;
import cn.ethan.core.agent.workflow.AgentQuestionCardModel;
import cn.ethan.core.agent.workflow.AgentWorkflowCheckpointModel;
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

    /**
     * 在同一 Turn 内执行一次受控纠正调用。默认实现保持旧协调器兼容，
     * 具体模型适配器可据此追加“必须形成终止决策”的纠正提示。
     */
    default AgentCoordinatorResult run(
            AgentThreadModel thread,
            AgentTurnModel turn,
            List<AgentItemModel> context,
            Map<String, String> answer,
            AgentExecutionContext executionContext,
            boolean correctionAttempt
    ) {
        return run(thread, turn, context, answer, executionContext);
    }

    record AgentCoordinatorResult(
            String assistantMessage,
            List<AgentItemDraft> items,
            String workflowRunId,
            boolean waitingUserInput,
            AgentDecisionTypeEnum decision,
            String decisionCode,
            AgentQuestionCardModel questionCard,
            AgentWorkflowCheckpointModel workflowCheckpoint,
            boolean correctionAttempt
    ) {
        /** 保留旧协调器实现的构造边界；没有显式控制 Tool 时由 Runtime 兼容处理。 */
        public AgentCoordinatorResult(
                String assistantMessage,
                List<AgentItemDraft> items,
                String workflowRunId,
                boolean waitingUserInput
        ) {
            this(assistantMessage, items, workflowRunId, waitingUserInput, null, null, null, null);
        }

        public AgentCoordinatorResult(
                String assistantMessage,
                List<AgentItemDraft> items,
                String workflowRunId,
                boolean waitingUserInput,
                AgentDecisionTypeEnum decision,
                String decisionCode
        ) {
            this(assistantMessage, items, workflowRunId, waitingUserInput,
                    decision, decisionCode, null, null);
        }

        /** 显式 QuestionCard 的构造边界；固定流程 Checkpoint 默认为空。 */
        public AgentCoordinatorResult(
                String assistantMessage,
                List<AgentItemDraft> items,
                String workflowRunId,
                boolean waitingUserInput,
                AgentDecisionTypeEnum decision,
                String decisionCode,
                AgentQuestionCardModel questionCard
        ) {
            this(assistantMessage, items, workflowRunId, waitingUserInput,
                    decision, decisionCode, questionCard, null, false);
        }

        /** 保留八参数构造边界；纠正标志只在协调器显式提供时写入。 */
        public AgentCoordinatorResult(
                String assistantMessage,
                List<AgentItemDraft> items,
                String workflowRunId,
                boolean waitingUserInput,
                AgentDecisionTypeEnum decision,
                String decisionCode,
                AgentQuestionCardModel questionCard,
                AgentWorkflowCheckpointModel workflowCheckpoint
        ) {
            this(assistantMessage, items, workflowRunId, waitingUserInput,
                    decision, decisionCode, questionCard, workflowCheckpoint, false);
        }

        public AgentCoordinatorResult {
            assistantMessage = assistantMessage == null ? "" : assistantMessage;
            items = items == null ? List.of() : List.copyOf(items);
            decisionCode = decisionCode == null || decisionCode.isBlank() ? null : decisionCode.trim();
        }
    }

    record AgentItemDraft(String type, String payload) {
    }
}
