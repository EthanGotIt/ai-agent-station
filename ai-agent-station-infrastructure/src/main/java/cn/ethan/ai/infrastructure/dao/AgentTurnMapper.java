package cn.ethan.ai.infrastructure.dao;

import cn.ethan.ai.infrastructure.dao.po.AgentTurnPO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AgentTurnMapper {

    int insert(AgentTurnPO turn);

    int updateByTurnId(AgentTurnPO turn);
}
