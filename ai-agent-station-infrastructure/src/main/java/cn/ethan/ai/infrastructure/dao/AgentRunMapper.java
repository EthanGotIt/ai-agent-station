package cn.ethan.ai.infrastructure.dao;

import cn.ethan.ai.infrastructure.dao.po.AgentRunPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

@Mapper
public interface AgentRunMapper {

    int insert(AgentRunPO run);

    int updateByRunId(AgentRunPO run);

    int cancelByRunId(@Param("runId") String runId,
                      @Param("cancelReason") String cancelReason,
                      @Param("updateTime") LocalDateTime updateTime);

}
