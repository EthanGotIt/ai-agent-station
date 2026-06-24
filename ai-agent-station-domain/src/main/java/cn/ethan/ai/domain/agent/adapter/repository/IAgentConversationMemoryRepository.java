package cn.ethan.ai.domain.agent.adapter.repository;

import cn.ethan.ai.domain.agent.model.valobj.AgentConversationMessageVO;
import cn.ethan.ai.domain.agent.model.valobj.AgentConversationSessionVO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Session 级短期记忆仓储。
 */
public interface IAgentConversationMemoryRepository {

    void save(AgentConversationMessageVO message);

    List<AgentConversationMessageVO> queryCompleteTurnMessages(String sessionId, long afterMessageId, int limit);

    AgentConversationSessionVO querySession(String sessionId);

    boolean createSession(AgentConversationSessionVO session);

    boolean updateSession(AgentConversationSessionVO session, int expectedVersion);

    void deleteSessionMemory(String sessionId);

    int deleteExpired(LocalDateTime now);

}
