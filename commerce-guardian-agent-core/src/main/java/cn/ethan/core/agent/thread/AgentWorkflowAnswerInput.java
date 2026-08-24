package cn.ethan.core.agent.thread;

import cn.ethan.core.agent.workflow.AgentWorkflowAnswerActionEnum;

import java.util.Map;

/**
 * 类型职责：保存回答 Turn 可持久恢复的 Workflow 检查点和结构化答案。
 *
 * @author ethan
 * @date 2026-08-21
 */
public record AgentWorkflowAnswerInput(
        String runId,
        String questionId,
        String checkpointId,
        long enqueuedQuestionVersion,
        Map<String, String> answers,
        AgentWorkflowAnswerActionEnum action
) {

    /** 保留历史调用方默认提交语义。 */
    public AgentWorkflowAnswerInput(
            String runId,
            String questionId,
            String checkpointId,
            long enqueuedQuestionVersion,
            Map<String, String> answers
    ) {
        this(runId, questionId, checkpointId, enqueuedQuestionVersion, answers,
                AgentWorkflowAnswerActionEnum.SUBMIT);
    }

    public AgentWorkflowAnswerInput {
        if (runId == null || runId.isBlank() || questionId == null || questionId.isBlank()
                || checkpointId == null || checkpointId.isBlank() || enqueuedQuestionVersion < 2) {
            throw new IllegalArgumentException("Workflow 回答检查点和入队版本必须有效");
        }
        action = action == null ? AgentWorkflowAnswerActionEnum.SUBMIT : action;
        answers = answers == null ? Map.of() : Map.copyOf(answers);
        if (action == AgentWorkflowAnswerActionEnum.SUBMIT && answers.isEmpty()) {
            throw new IllegalArgumentException("Workflow 提交回答不能为空");
        }
        if (action == AgentWorkflowAnswerActionEnum.CANCEL && !answers.isEmpty()) {
            answers = Map.of();
        }
    }

    /** 返回 admission 时客户端提交的 AVAILABLE 版本。 */
    public long admissionExpectedVersion() {
        return enqueuedQuestionVersion - 2;
    }
}
