package cn.ethan.infrastructure.agent.thread.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * 类型职责：访问持久化 QuestionCard 和版本控制字段。
 *
 * @author ethan
 * @date 2026-08-19
 */
@Mapper
public interface AgentWorkflowQuestionMapper extends BaseMapper<AgentWorkflowQuestionEntity> {

    @Select("SELECT * FROM AGENT_WORKFLOW_QUESTION WHERE USER_ID = #{userId} AND THREAD_ID = #{threadId} AND STATUS = 'OPEN' ORDER BY CREATED_AT DESC LIMIT 1")
    AgentWorkflowQuestionEntity selectOpen(String userId, String threadId);

    @Select("SELECT * FROM AGENT_WORKFLOW_QUESTION WHERE USER_ID = #{userId} AND RUN_ID = #{runId} AND STATUS = 'OPEN' LIMIT 1")
    AgentWorkflowQuestionEntity selectOpenByRun(String userId, String runId);

}
