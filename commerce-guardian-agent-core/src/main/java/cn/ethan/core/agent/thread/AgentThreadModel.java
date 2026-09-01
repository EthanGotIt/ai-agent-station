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
        String openQuestionId,
        AgentInteractionTypeEnum openInteractionType,
        String openInteractionId
) {
    public static final int MAX_THREAD_ID_LENGTH = 64;
    public static final int MAX_USER_ID_LENGTH = 128;
    public static final int MAX_TITLE_LENGTH = 256;
    public static final int MAX_CONTEXT_TYPE_LENGTH = 64;
    public static final int MAX_CONTEXT_ID_LENGTH = 128;

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
        this(threadId, userId, title, status, contextType, contextId, nextSequence, createdAt, updatedAt,
                null, null, null);
    }

    /** 保留旧持久化边界；旧 OPEN_QUESTION_ID 等同于 QUESTION_CARD 引用。 */
    public AgentThreadModel(
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
        this(threadId, userId, title, status, contextType, contextId, nextSequence, createdAt, updatedAt,
                openQuestionId, openQuestionId == null ? null : AgentInteractionTypeEnum.QUESTION_CARD,
                openQuestionId);
    }

    public AgentThreadModel {
        threadId = normalizeIdentity(threadId, "threadId", MAX_THREAD_ID_LENGTH);
        userId = normalizeIdentity(userId, "userId", MAX_USER_ID_LENGTH);
        title = title == null || title.isBlank() ? "未命名 Agent Thread" : title.trim();
        if (title.length() > MAX_TITLE_LENGTH) {
            throw new IllegalArgumentException("title 长度不能超过 " + MAX_TITLE_LENGTH);
        }
        contextType = normalizeOptional(contextType, "contextType", MAX_CONTEXT_TYPE_LENGTH);
        contextId = normalizeOptional(contextId, "contextId", MAX_CONTEXT_ID_LENGTH);
        status = status == null ? AgentThreadStatusEnum.ACTIVE : status;
        nextSequence = Math.max(nextSequence, 0L);
        openQuestionId = openQuestionId == null || openQuestionId.isBlank() ? null : openQuestionId.trim();
        openInteractionType = openInteractionType == null && openInteractionId != null
                ? AgentInteractionTypeEnum.QUESTION_CARD : openInteractionType;
        openInteractionId = openInteractionId == null || openInteractionId.isBlank()
                ? openQuestionId : openInteractionId.trim();
        if (openInteractionId == null && openInteractionType != null) {
            throw new IllegalArgumentException("开放交互类型必须关联 interactionId");
        }
        if (openInteractionId != null && openInteractionType == null) {
            throw new IllegalArgumentException("开放交互必须声明 interactionType");
        }
        if (openInteractionType == AgentInteractionTypeEnum.QUESTION_CARD && openQuestionId == null) {
            openQuestionId = openInteractionId;
        }
        if (openInteractionType != AgentInteractionTypeEnum.QUESTION_CARD) {
            openQuestionId = null;
        }
    }

    private static String normalizeIdentity(String value, String name, int maxLength) {
        String normalized = value == null ? null : value.trim();
        if (normalized == null || normalized.isBlank() || normalized.length() > maxLength) {
            throw new IllegalArgumentException(name + " 不能为空且长度不能超过 " + maxLength);
        }
        return normalized;
    }

    private static String normalizeOptional(String value, String name, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(name + " 长度不能超过 " + maxLength);
        }
        return normalized;
    }

    public AgentThreadModel withOpenQuestion(String questionId, Instant at) {
        return withOpenInteraction(AgentInteractionTypeEnum.QUESTION_CARD, questionId, at);
    }

    public AgentThreadModel withoutOpenQuestion(Instant at) {
        return withoutOpenInteraction(at);
    }

    public AgentThreadModel withOpenInteraction(AgentInteractionTypeEnum type, String interactionId, Instant at) {
        if (type == null || interactionId == null || interactionId.isBlank()) {
            throw new IllegalArgumentException("开放交互类型和 ID 不能为空");
        }
        return new AgentThreadModel(threadId, userId, title, status, contextType, contextId,
                nextSequence, createdAt, at,
                type == AgentInteractionTypeEnum.QUESTION_CARD ? interactionId : null, type, interactionId);
    }

    public AgentThreadModel withoutOpenInteraction(Instant at) {
        return new AgentThreadModel(threadId, userId, title, status, contextType, contextId,
                nextSequence, createdAt, at, null, null, null);
    }
}
