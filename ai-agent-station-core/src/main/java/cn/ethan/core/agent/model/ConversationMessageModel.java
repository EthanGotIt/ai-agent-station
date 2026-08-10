package cn.ethan.core.agent.model;

import cn.ethan.core.agent.enums.ConversationRoleEnum;

/**
 * 会话消息模型：不包含原始 Thinking、工具参数或内部工作流上下文。
 *
 * @author ethan
 * @date 2026-08-07
 */
public record ConversationMessageModel(ConversationRoleEnum role, String content) {

    public ConversationMessageModel {
        if (role == null || content == null || content.isBlank()) {
            throw new IllegalArgumentException("conversation role and content are required");
        }
        content = content.trim();
    }
}
