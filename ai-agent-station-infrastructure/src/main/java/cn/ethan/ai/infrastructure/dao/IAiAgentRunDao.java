package cn.ethan.ai.infrastructure.dao;

import cn.ethan.ai.infrastructure.dao.po.AiAgentRun;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface IAiAgentRunDao {

    int insert(AiAgentRun aiAgentRun);

    int updateByRunId(AiAgentRun aiAgentRun);

    AiAgentRun queryByRunId(@Param("runId") String runId);

    int cancelByRunId(@Param("runId") String runId, @Param("cancelReason") String cancelReason, @Param("updateTime") java.time.LocalDateTime updateTime);

}
