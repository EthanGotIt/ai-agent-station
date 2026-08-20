package cn.ethan.core.agent.execution;

import java.util.Map;

/**
 * 类型职责：描述一次需要原子预留并持久化的 Workflow 回答请求。
 *
 * @author ethan
 * @date 2026-08-21
 */
public record AgentWorkflowAnswerAdmissionCommand(
        String userId,
        String threadId,
        String clientRequestId,
        int queuePosition,
        String runId,
        String questionId,
        String checkpointId,
        long expectedVersion,
        Map<String, String> answers
) {

    public AgentWorkflowAnswerAdmissionCommand {
        if (userId == null || userId.isBlank() || threadId == null || threadId.isBlank()
                || clientRequestId == null || clientRequestId.isBlank() || clientRequestId.length() > 128
                || runId == null || runId.isBlank() || questionId == null || questionId.isBlank()
                || checkpointId == null || checkpointId.isBlank()) {
            throw new IllegalArgumentException("Workflow 回答 admission 标识不能为空");
        }
        if (queuePosition < 1 || expectedVersion < 0 || answers == null || answers.isEmpty()) {
            throw new IllegalArgumentException("Workflow 回答 admission 参数无效");
        }
        answers = Map.copyOf(answers);
    }
}
