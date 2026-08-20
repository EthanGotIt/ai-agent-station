package cn.ethan.infrastructure.agent.thread.persistence;

import cn.ethan.infrastructure.agent.thread.persistence.AgentContextSnapshotEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * 类型职责：读取和保存 Thread 的最新上下文摘要快照。
 *
 * @author ethan
 * @date 2026-08-19
 */
@Mapper
public interface AgentContextSnapshotMapper extends BaseMapper<AgentContextSnapshotEntity> {

    @Select("SELECT * FROM AGENT_CONTEXT_SNAPSHOT WHERE THREAD_ID = #{threadId} ORDER BY VERSION_NO DESC LIMIT 1")
    AgentContextSnapshotEntity selectLatest(String threadId);
}
