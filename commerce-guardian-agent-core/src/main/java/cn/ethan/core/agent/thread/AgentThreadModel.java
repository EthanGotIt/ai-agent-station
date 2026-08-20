package cn.ethan.core.agent.thread;


import java.time.Instant;

/**
 * Agent Thread：可恢复的对话与执行上下文根。
 *
 * @author ethan
 * @date 2026-08-19
 */
public record AgentThreadModel(
        String threadId,
        String userId,
        String title,
        AgentThreadStatusEnum status,
        String contextType,
        String contextId,
        long nextSequence,
        Instant createdAt,
        Instant updatedAt,
        String openQuestionId
) {
    public AgentThreadModel(
            String threadId,
            String userId,
            String title,
            AgentThreadStatusEnum status,
            String contextType,
            String contextId,
            long nextSequence,
            Instant createdAt,
            Instant updatedAt
    ) {
        this(threadId, userId, title, status, contextType, contextId, nextSequence, createdAt, updatedAt, null);
    }

    public AgentThreadModel {
        if (threadId == null || threadId.isBlank() || userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("threadId and userId must not be blank");
        }
        title = title == null || title.isBlank() ? "未命名 Agent Thread" : title.trim();
        status = status == null ? AgentThreadStatusEnum.ACTIVE : status;
        nextSequence = Math.max(nextSequence, 0L);
        openQuestionId = openQuestionId == null || openQuestionId.isBlank() ? null : openQuestionId.trim();
    }

    public AgentThreadModel withOpenQuestion(String questionId, Instant at) {
        return new AgentThreadModel(threadId, userId, title, status, contextType, contextId,
                nextSequence, createdAt, at, questionId);
    }

    public AgentThreadModel withoutOpenQuestion(Instant at) {
        return new AgentThreadModel(threadId, userId, title, status, contextType, contextId,
                nextSequence, createdAt, at, null);
    }
}
