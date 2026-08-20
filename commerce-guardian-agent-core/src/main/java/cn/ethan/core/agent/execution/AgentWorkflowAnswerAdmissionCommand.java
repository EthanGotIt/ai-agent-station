package cn.ethan.core.agent.execution;

import cn.ethan.core.agent.thread.AgentThreadModel;
import cn.ethan.core.agent.thread.AgentTurnModel;

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
        userId = normalizeIdentity(userId, "userId", AgentThreadModel.MAX_USER_ID_LENGTH);
        threadId = normalizeIdentity(threadId, "threadId", AgentThreadModel.MAX_THREAD_ID_LENGTH);
        clientRequestId = normalizeIdentity(clientRequestId, "clientRequestId",
                AgentTurnModel.MAX_CLIENT_REQUEST_ID_LENGTH);
        if (runId == null || runId.isBlank() || questionId == null || questionId.isBlank()
                || checkpointId == null || checkpointId.isBlank()) {
            throw new IllegalArgumentException("Workflow 回答 admission 标识不能为空");
        }
        if (queuePosition < 1 || expectedVersion < 0 || answers == null || answers.isEmpty()) {
            throw new IllegalArgumentException("Workflow 回答 admission 参数无效");
        }
        answers = Map.copyOf(answers);
    }

    private static String normalizeIdentity(String value, String name, int maxLength) {
        String normalized = value == null ? null : value.trim();
        if (normalized == null || normalized.isBlank() || normalized.length() > maxLength) {
            throw new IllegalArgumentException(name + " 不能为空且长度不能超过 " + maxLength);
        }
        return normalized;
    }
}
