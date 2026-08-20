package cn.ethan.infrastructure.agent.thread.persistence;

import cn.ethan.infrastructure.agent.thread.persistence.AgentTurnEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 类型职责：访问 Turn 生命周期和重启恢复所需的排队记录。
 *
 * @author ethan
 * @date 2026-08-19
 */
@Mapper
public interface AgentTurnMapper extends BaseMapper<AgentTurnEntity> {

    @Select("SELECT * FROM AGENT_TURN WHERE USER_ID = #{userId} AND CLIENT_REQUEST_ID = #{clientRequestId}")
    AgentTurnEntity selectByRequest(String userId, String clientRequestId);

    @Select("SELECT * FROM AGENT_TURN WHERE USER_ID = #{userId} AND THREAD_ID = #{threadId} ORDER BY CREATED_AT")
    List<AgentTurnEntity> selectByThread(String userId, String threadId);

    @Select("SELECT * FROM AGENT_TURN WHERE STATUS IN ('QUEUED', 'ACTIVE') ORDER BY CREATED_AT")
    List<AgentTurnEntity> selectRecoverable();
}
