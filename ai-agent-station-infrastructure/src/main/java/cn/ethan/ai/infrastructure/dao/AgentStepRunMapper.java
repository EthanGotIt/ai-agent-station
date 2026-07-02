package cn.ethan.ai.infrastructure.dao;

import cn.ethan.ai.infrastructure.dao.po.AgentStepRunPO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AgentStepRunMapper {

    int insert(AgentStepRunPO step);

}
