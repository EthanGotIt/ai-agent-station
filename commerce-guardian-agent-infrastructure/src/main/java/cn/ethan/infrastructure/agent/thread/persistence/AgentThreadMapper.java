package cn.ethan.infrastructure.agent.thread.persistence;

import cn.ethan.infrastructure.agent.thread.persistence.AgentThreadEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 类型职责：访问 Agent Thread 元数据，并在分配 Item 序号时提供行锁。
 *
 * @author ethan
 * @date 2026-08-19
 */
@Mapper
public interface AgentThreadMapper extends BaseMapper<AgentThreadEntity> {

    @Select("SELECT * FROM AGENT_THREAD WHERE THREAD_ID = #{threadId} FOR UPDATE")
    AgentThreadEntity selectForUpdate(String threadId);

    @Select("SELECT * FROM AGENT_THREAD WHERE USER_ID = #{userId} ORDER BY UPDATED_AT DESC")
    List<AgentThreadEntity> selectByUser(String userId);
}
