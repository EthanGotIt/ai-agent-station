package cn.ethan.ai.infrastructure.dao;

import cn.ethan.ai.infrastructure.dao.po.AgentRunPO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AgentRunMapper {

    int insert(AgentRunPO run);

    int updateByRunId(AgentRunPO run);

    int countByTurnId(String turnId);

}
