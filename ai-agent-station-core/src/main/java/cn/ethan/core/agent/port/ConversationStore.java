package cn.ethan.core.agent.port;

import cn.ethan.core.agent.model.ConversationMessageModel;

import java.util.List;

/**
 * 会话存储端口：管理连续对话上下文，与 Pending Input 和未来 Workflow Run 分离。
 *
 * @author ethan
 * @date 2026-08-07
 */
public interface ConversationStore {

    List<ConversationMessageModel> recent(String userId, String sessionId, int maxMessages,
                                          int maxCharacters);

    void append(String userId, String sessionId, ConversationMessageModel message);
}
