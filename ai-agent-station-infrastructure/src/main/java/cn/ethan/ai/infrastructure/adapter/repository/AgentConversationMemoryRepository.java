package cn.ethan.ai.infrastructure.adapter.repository;

import cn.ethan.ai.domain.agent.adapter.repository.IAgentConversationMemoryRepository;
import cn.ethan.ai.domain.agent.model.valobj.AgentConversationMessageVO;
import cn.ethan.ai.domain.agent.model.valobj.AgentConversationSessionVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.AgentConversationMessageRoleEnumVO;
import cn.ethan.ai.infrastructure.dao.IAiAgentConversationMessageDao;
import cn.ethan.ai.infrastructure.dao.IAiAgentConversationSessionDao;
import cn.ethan.ai.infrastructure.dao.po.AiAgentConversationMessage;
import cn.ethan.ai.infrastructure.dao.po.AiAgentConversationSession;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

@Repository
public class AgentConversationMemoryRepository implements IAgentConversationMemoryRepository {

    @Resource
    private IAiAgentConversationMessageDao messageDao;

    @Resource
    private IAiAgentConversationSessionDao sessionDao;

    @Override
    public void save(AgentConversationMessageVO message) {
        AiAgentConversationMessage po = AiAgentConversationMessage.builder()
                .sessionId(message.getSessionId()).runId(message.getRunId()).role(nameOf(message.getRole()))
                .content(message.getContent())
                .createTime(message.getCreateTime() == null ? LocalDateTime.now() : message.getCreateTime())
                .build();
        messageDao.insert(po);
        message.setId(po.getId());
    }

    @Override
    public List<AgentConversationMessageVO> queryCompleteTurnMessages(String sessionId, long afterMessageId, int limit) {
        List<AiAgentConversationMessage> messages = messageDao.queryCompleteTurnMessages(sessionId, afterMessageId, limit);
        if (messages == null || messages.isEmpty()) {
            return Collections.emptyList();
        }
        return messages.stream().map(this::toMessage).toList();
    }

    @Override
    public AgentConversationSessionVO querySession(String sessionId) {
        AiAgentConversationSession session = sessionDao.queryBySessionId(sessionId);
        return session == null ? null : AgentConversationSessionVO.builder()
                .sessionId(session.getSessionId()).summaryJson(session.getSummaryJson())
                .summarizedMessageId(session.getSummarizedMessageId()).version(session.getVersion())
                .expiresAt(session.getExpiresAt()).updateTime(session.getUpdateTime()).build();
    }

    @Override
    public boolean createSession(AgentConversationSessionVO session) {
        return sessionDao.insertIgnore(toSession(session)) == 1;
    }

    @Override
    public boolean updateSession(AgentConversationSessionVO session, int expectedVersion) {
        return sessionDao.updateOptimistic(toSession(session), expectedVersion) == 1;
    }

    @Override
    @Transactional
    public void deleteSessionMemory(String sessionId) {
        messageDao.deleteBySessionId(sessionId);
        sessionDao.deleteBySessionId(sessionId);
    }

    @Override
    @Transactional
    public int deleteExpired(LocalDateTime now) {
        messageDao.deleteExpiredSessionMessages(now);
        return sessionDao.deleteExpired(now);
    }

    private AgentConversationMessageVO toMessage(AiAgentConversationMessage message) {
        return AgentConversationMessageVO.builder()
                .id(message.getId()).sessionId(message.getSessionId()).runId(message.getRunId())
                .role(roleOf(message.getRole())).content(message.getContent()).createTime(message.getCreateTime()).build();
    }

    private AiAgentConversationSession toSession(AgentConversationSessionVO session) {
        return AiAgentConversationSession.builder()
                .sessionId(session.getSessionId()).summaryJson(session.getSummaryJson())
                .summarizedMessageId(session.getSummarizedMessageId()).version(session.getVersion())
                .expiresAt(session.getExpiresAt()).updateTime(session.getUpdateTime()).build();
    }

    private AgentConversationMessageRoleEnumVO roleOf(String role) {
        return role == null ? null : AgentConversationMessageRoleEnumVO.valueOf(role);
    }

    private String nameOf(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
