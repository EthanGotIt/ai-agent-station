package cn.ethan.ai.infrastructure.adapter.repository;

import cn.ethan.ai.domain.agent.adapter.repository.IAgentConversationMemoryRepository;
import cn.ethan.ai.domain.agent.model.valobj.AgentConversationMessageVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.AgentConversationMessageRoleEnumVO;
import cn.ethan.ai.infrastructure.dao.IAiAgentConversationMessageDao;
import cn.ethan.ai.infrastructure.dao.po.AiAgentConversationMessage;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * Session 级短期记忆仓储实现。
 */
@Repository
public class AgentConversationMemoryRepository implements IAgentConversationMemoryRepository {

    @Resource
    private IAiAgentConversationMessageDao dao;

    @Override
    public void save(AgentConversationMessageVO message) {
        dao.insert(AiAgentConversationMessage.builder()
                .sessionId(message.getSessionId())
                .runId(message.getRunId())
                .role(nameOf(message.getRole()))
                .content(message.getContent())
                .contentSummary(message.getContentSummary())
                .contextUnits(message.getContextUnits())
                .createTime(message.getCreateTime() == null ? LocalDateTime.now() : message.getCreateTime())
                .build());
    }

    @Override
    public List<AgentConversationMessageVO> queryRecentMessages(String sessionId, int limit) {
        List<AiAgentConversationMessage> messages = dao.queryRecentBySessionId(sessionId, limit);
        if (messages == null || messages.isEmpty()) {
            return Collections.emptyList();
        }
        return messages.stream()
                .map(message -> AgentConversationMessageVO.builder()
                        .id(message.getId())
                        .sessionId(message.getSessionId())
                        .runId(message.getRunId())
                        .role(roleOf(message.getRole()))
                        .content(message.getContent())
                        .contentSummary(message.getContentSummary())
                        .contextUnits(message.getContextUnits())
                        .createTime(message.getCreateTime())
                        .build())
                .toList();
    }

    private AgentConversationMessageRoleEnumVO roleOf(String role) {
        return role == null ? null : AgentConversationMessageRoleEnumVO.valueOf(role);
    }

    private String nameOf(Enum<?> value) {
        return value == null ? null : value.name();
    }

}
