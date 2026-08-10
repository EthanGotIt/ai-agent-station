package cn.ethan.core.agent.support;

import cn.ethan.core.agent.model.ConversationMessageModel;
import cn.ethan.core.agent.port.ConversationStore;

import java.util.List;

/**
 * 无状态会话存储：供不需要会话适配器的内核测试或嵌入式运行使用。
 *
 * @author ethan
 * @date 2026-08-07
 */
public final class NoOpConversationStore implements ConversationStore {

    @Override
    public List<ConversationMessageModel> recent(String userId, String sessionId, int maxMessages,
                                                  int maxCharacters) {
        return List.of();
    }

    @Override
    public void append(String userId, String sessionId, ConversationMessageModel message) {
        // 有意忽略：该实现仅负责保持调用端无需判空。
    }
}
