package cn.ethan.ai.infrastructure.dao;

import cn.ethan.ai.infrastructure.dao.po.AiAgentConversationMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface IAiAgentConversationMessageDao {

    int insert(AiAgentConversationMessage message);

    List<AiAgentConversationMessage> queryCompleteTurnMessages(@Param("sessionId") String sessionId,
                                                               @Param("afterMessageId") long afterMessageId,
                                                               @Param("limit") int limit);

    int deleteBySessionId(@Param("sessionId") String sessionId);

    int deleteExpiredSessionMessages(@Param("now") java.time.LocalDateTime now);

}
