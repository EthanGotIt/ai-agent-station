package cn.ethan.ai.infrastructure.dao;

import cn.ethan.ai.infrastructure.dao.po.AiAgentStepRun;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface IAiAgentStepRunDao {

    int insert(AiAgentStepRun aiAgentStepRun);

    int updateByRunIdAndStepId(AiAgentStepRun aiAgentStepRun);

    int cancelRunningByRunId(@Param("runId") String runId,
                             @Param("cancelReason") String cancelReason,
                             @Param("updateTime") java.time.LocalDateTime updateTime);

    List<AiAgentStepRun> queryByRunId(@Param("runId") String runId);

}
