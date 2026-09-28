package cn.ethan.infrastructure.agent.thread.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 类型职责：访问 WorkflowRun 持久化记录。
 *
 * @author ethan
 * @date 2026-08-19
 */
@Mapper
public interface AgentWorkflowRunMapper extends BaseMapper<AgentWorkflowRunEntity> {

    @Select("SELECT * FROM AGENT_WORKFLOW_RUN WHERE USER_ID = #{userId} AND RUN_ID = #{runId}")
    AgentWorkflowRunEntity selectOwned(String userId, String runId);

    @Select("SELECT * FROM AGENT_WORKFLOW_RUN WHERE USER_ID = #{userId} AND RUN_ID = #{runId} FOR UPDATE")
    AgentWorkflowRunEntity selectOwnedForUpdate(String userId, String runId);

    @Select("SELECT * FROM AGENT_WORKFLOW_RUN WHERE USER_ID = #{userId} "
            + "AND TURN_ID = #{turnId} AND WORKFLOW_TYPE = #{workflowType} LIMIT 1")
    AgentWorkflowRunEntity selectBySource(String userId, String turnId, String workflowType);

    @Select("SELECT * FROM AGENT_WORKFLOW_RUN WHERE USER_ID = #{userId} AND THREAD_ID = #{threadId} "
            + "ORDER BY UPDATED_AT DESC, RUN_ID DESC LIMIT #{limit}")
    List<AgentWorkflowRunEntity> selectRecent(String userId, String threadId, int limit);
}
