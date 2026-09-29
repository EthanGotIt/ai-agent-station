package cn.ethan.infrastructure.agent.thread.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 类型职责：访问 WorkflowTask 持久化记录。
 *
 * @author ethan
 * @date 2026-08-19
 */
@Mapper
public interface AgentWorkflowTaskMapper extends BaseMapper<AgentWorkflowTaskEntity> {

    @Select("SELECT RUN_ID AS TASK_ID, THREAD_ID, TURN_ID, USER_ID, WORKFLOW_TYPE, ORCHESTRATION_VERSION, "
            + "STATUS, VERSION_NO, STEPS_JSON, STATE_JSON, CREATED_AT, UPDATED_AT "
            + "FROM AGENT_WORKFLOW_RUN WHERE USER_ID = #{userId} AND RUN_ID = #{taskId}")
    AgentWorkflowTaskEntity selectOwned(String userId, String taskId);

    @Select("SELECT RUN_ID AS TASK_ID, THREAD_ID, TURN_ID, USER_ID, WORKFLOW_TYPE, ORCHESTRATION_VERSION, "
            + "STATUS, VERSION_NO, STEPS_JSON, STATE_JSON, CREATED_AT, UPDATED_AT "
            + "FROM AGENT_WORKFLOW_RUN WHERE USER_ID = #{userId} AND RUN_ID = #{taskId} FOR UPDATE")
    AgentWorkflowTaskEntity selectOwnedForUpdate(String userId, String taskId);

    @Select("SELECT RUN_ID AS TASK_ID, THREAD_ID, TURN_ID, USER_ID, WORKFLOW_TYPE, ORCHESTRATION_VERSION, "
            + "STATUS, VERSION_NO, STEPS_JSON, STATE_JSON, CREATED_AT, UPDATED_AT "
            + "FROM AGENT_WORKFLOW_RUN WHERE USER_ID = #{userId} "
            + "AND TURN_ID = #{turnId} AND WORKFLOW_TYPE = #{workflowType} LIMIT 1")
    AgentWorkflowTaskEntity selectBySource(String userId, String turnId, String workflowType);

    @Select("SELECT RUN_ID AS TASK_ID, THREAD_ID, TURN_ID, USER_ID, WORKFLOW_TYPE, ORCHESTRATION_VERSION, "
            + "STATUS, VERSION_NO, STEPS_JSON, STATE_JSON, CREATED_AT, UPDATED_AT "
            + "FROM AGENT_WORKFLOW_RUN WHERE USER_ID = #{userId} AND THREAD_ID = #{threadId} "
            + "ORDER BY UPDATED_AT DESC, RUN_ID DESC LIMIT #{limit}")
    List<AgentWorkflowTaskEntity> selectRecent(String userId, String threadId, int limit);
}
