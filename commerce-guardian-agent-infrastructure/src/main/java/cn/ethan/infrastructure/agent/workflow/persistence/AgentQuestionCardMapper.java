package cn.ethan.infrastructure.agent.workflow.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * 类型职责：访问独立 QuestionCard 事实。
 *
 * @author ethan
 * @date 2026-08-27
 */
@Mapper
public interface AgentQuestionCardMapper extends BaseMapper<AgentQuestionCardEntity> {

    @Select("SELECT * FROM AGENT_QUESTION_CARD WHERE USER_ID = #{userId} "
            + "AND THREAD_ID = #{threadId} AND STATUS = 'OPEN' ORDER BY CREATED_AT DESC LIMIT 1")
    AgentQuestionCardEntity selectOpen(String userId, String threadId);
}
