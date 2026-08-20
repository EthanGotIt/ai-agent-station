package cn.ethan.core.agent.thread;

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
        Map<String, String> answers
) {

    public AgentWorkflowAnswerInput {
        if (runId == null || runId.isBlank() || questionId == null || questionId.isBlank()
                || checkpointId == null || checkpointId.isBlank() || enqueuedQuestionVersion < 2) {
            throw new IllegalArgumentException("Workflow 回答检查点和入队版本必须有效");
        }
        if (answers == null || answers.isEmpty()) {
            throw new IllegalArgumentException("Workflow 回答不能为空");
        }
        answers = Map.copyOf(answers);
    }

    /** 返回 admission 时客户端提交的 AVAILABLE 版本。 */
    public long admissionExpectedVersion() {
        return enqueuedQuestionVersion - 2;
    }
}
