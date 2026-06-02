package cn.ethan.ai.infrastructure.dao;

import cn.ethan.ai.infrastructure.dao.po.AiAgentRuntimeConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface IAiAgentRuntimeConfigDao {

    AiAgentRuntimeConfig queryByAgentId(@Param("agentId") String agentId);

}
