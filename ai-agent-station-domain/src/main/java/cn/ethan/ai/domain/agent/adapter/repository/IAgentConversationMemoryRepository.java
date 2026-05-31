package cn.ethan.ai.domain.agent.adapter.repository;

import cn.ethan.ai.domain.agent.model.valobj.AgentConversationMessageVO;

import java.util.List;

/**
 * Session 级短期记忆仓储。
 */
public interface IAgentConversationMemoryRepository {

    void save(AgentConversationMessageVO message);

    List<AgentConversationMessageVO> queryRecentMessages(String sessionId, int limit);

}
