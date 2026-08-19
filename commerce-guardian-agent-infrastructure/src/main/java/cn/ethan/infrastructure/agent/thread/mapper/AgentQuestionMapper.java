package cn.ethan.infrastructure.agent.thread.mapper;

import cn.ethan.infrastructure.agent.thread.entity.AgentQuestionEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 类型职责：访问持久化 QuestionCard 和版本控制字段。
 *
 * @author ethan
 * @date 2026-08-19
 */
@Mapper
public interface AgentQuestionMapper extends BaseMapper<AgentQuestionEntity> {

    @Select("SELECT * FROM AGENT_WORKFLOW_QUESTION WHERE USER_ID = #{userId} AND THREAD_ID = #{threadId} AND STATUS = 'OPEN' ORDER BY CREATED_AT DESC LIMIT 1")
    AgentQuestionEntity selectOpen(String userId, String threadId);

    @Select("SELECT * FROM AGENT_WORKFLOW_QUESTION WHERE USER_ID = #{userId} AND RUN_ID = #{runId} AND STATUS = 'OPEN' LIMIT 1")
    AgentQuestionEntity selectOpenByRun(String userId, String runId);

    @Select("SELECT * FROM AGENT_WORKFLOW_QUESTION WHERE THREAD_ID = #{threadId} AND STATUS = 'OPEN' FOR UPDATE")
    List<AgentQuestionEntity> selectOpenForUpdate(String threadId);
}
