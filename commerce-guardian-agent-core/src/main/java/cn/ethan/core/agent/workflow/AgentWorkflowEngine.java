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

    /**
     * Workflow 启动结果：固定流程只能返回独立 QuestionCard 或执行 Checkpoint，旧问题模型仅供迁移期读取。
     */
    record StartResult(
            String runId,
            AgentQuestionCardModel questionCard,
            AgentWorkflowCheckpointModel checkpoint,
            AgentWorkflowQuestionModel legacyQuestion
    ) {
        public StartResult(String runId, AgentWorkflowQuestionModel question) {
            this(runId, null, null, question);
        }

        public StartResult(String runId, AgentQuestionCardModel questionCard,
                           AgentWorkflowCheckpointModel checkpoint) {
            this(runId, questionCard, checkpoint, null);
        }

        /** 迁移期旧调用方读取的 QuestionCard；新代码应使用 questionCard。 */
        public AgentWorkflowQuestionModel question() {
            return legacyQuestion;
        }
    }

    record ResumeResult(
            String message,
            String resultStatus,
            ExternalActionCommandModel command,
            AgentQuestionCardModel questionCard,
            AgentWorkflowCheckpointModel checkpoint,
            AgentWorkflowQuestionModel legacyQuestion
    ) {
        public ResumeResult(String message, String resultStatus, ExternalActionCommandModel command) {
            this(message, resultStatus, command, null, null, null);
        }

        public ResumeResult(String message, String resultStatus, ExternalActionCommandModel command,
                            AgentWorkflowQuestionModel legacyQuestion) {
            this(message, resultStatus, command, null, null, legacyQuestion);
        }

        public ResumeResult(String message, String resultStatus, ExternalActionCommandModel command,
                            AgentQuestionCardModel questionCard,
                            AgentWorkflowCheckpointModel checkpoint) {
            this(message, resultStatus, command, questionCard, checkpoint, null);
        }

        /** 迁移期旧调用方读取的 QuestionCard；新代码应使用 questionCard。 */
        public AgentWorkflowQuestionModel question() {
            return legacyQuestion;
        }

        public ResumeResult {
            message = message == null ? "" : message;
            resultStatus = resultStatus == null ? "" : resultStatus;
        }
    }
}
