package cn.ethan.ai.infrastructure.dao;

import cn.ethan.ai.infrastructure.dao.po.AgentTurnPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AgentTurnMapper {

    int insert(AgentTurnPO turn);

    int updateByTurnId(AgentTurnPO turn);

    int countByCaseId(@Param("caseId") String caseId);
}
