package cn.ethan.core.agent.model;

import cn.ethan.core.agent.enums.AgentMemorySourceEnum;

/**
 * 后台记忆提取输入：仅在完成回合后组装，永不阻塞当前用户响应。
 *
 * @author ethan
 * @date 2026-08-10
 */
public record AgentMemoryExtractionInputModel(
        String userId,
        String sessionId,
        String requestId,
        AgentMemorySourceEnum sourceType,
        String userContent,
        String finalContent
) {

    public AgentMemoryExtractionInputModel {
        if (isBlank(userId) || isBlank(sessionId) || isBlank(requestId)
                || sourceType == null || isBlank(userContent) || isBlank(finalContent)) {
            throw new IllegalArgumentException("memory extraction input is incomplete");
        }
        userId = userId.strip();
        sessionId = sessionId.strip();
        requestId = requestId.strip();
        userContent = userContent.strip();
        finalContent = finalContent.strip();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
