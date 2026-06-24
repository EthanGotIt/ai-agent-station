package cn.ethan.ai.infrastructure.dao;

import cn.ethan.ai.infrastructure.dao.po.AiAgentConversationSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

@Mapper
public interface IAiAgentConversationSessionDao {

    AiAgentConversationSession queryBySessionId(@Param("sessionId") String sessionId);

    int insertIgnore(AiAgentConversationSession session);

    int updateOptimistic(@Param("session") AiAgentConversationSession session,
                         @Param("expectedVersion") int expectedVersion);

    int deleteBySessionId(@Param("sessionId") String sessionId);

    int deleteExpired(@Param("now") LocalDateTime now);
}
