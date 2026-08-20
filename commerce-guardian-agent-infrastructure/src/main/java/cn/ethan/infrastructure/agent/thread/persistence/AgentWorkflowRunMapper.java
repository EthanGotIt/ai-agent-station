package cn.ethan.infrastructure.agent.thread.persistence;

import cn.ethan.infrastructure.agent.thread.persistence.AgentWorkflowRunEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

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
}
