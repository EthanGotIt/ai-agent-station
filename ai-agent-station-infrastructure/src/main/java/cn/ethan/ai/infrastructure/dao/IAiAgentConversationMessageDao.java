package cn.ethan.ai.infrastructure.dao;

import cn.ethan.ai.infrastructure.dao.po.AiAgentConversationMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface IAiAgentConversationMessageDao {

    int insert(AiAgentConversationMessage message);

    List<AiAgentConversationMessage> queryRecentBySessionId(@Param("sessionId") String sessionId,
                                                            @Param("limit") int limit);

}
