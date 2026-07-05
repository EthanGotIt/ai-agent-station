package cn.ethan.ai.infrastructure.dao;

import cn.ethan.ai.infrastructure.dao.po.AgentStepPO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AgentStepMapper {

    int insert(AgentStepPO step);
}
