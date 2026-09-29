package cn.ethan.infrastructure.agent.thread.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * 类型职责：按客户端幂等键和 Turn 状态读取恢复信号。
 *
 * @author ethan
 * @date 2026-09-29
 */
@Mapper
public interface AgentTurnResumeSignalMapper extends BaseMapper<AgentTurnResumeSignalEntity> {

    @Select("SELECT * FROM AGENT_TURN_RESUME_SIGNAL WHERE USER_ID = #{userId} AND REQUEST_ID = #{requestId}")
    AgentTurnResumeSignalEntity selectByRequest(String userId, String requestId);

    @Select("SELECT * FROM AGENT_TURN_RESUME_SIGNAL WHERE USER_ID = #{userId} AND TURN_ID = #{turnId} "
            + "AND STATUS = 'PENDING' ORDER BY CREATED_AT, SIGNAL_ID LIMIT 1")
    AgentTurnResumeSignalEntity selectPending(String userId, String turnId);
}
