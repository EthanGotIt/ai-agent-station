package cn.ethan.infrastructure.agent.thread.persistence;

import cn.ethan.infrastructure.agent.thread.persistence.AgentItemEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 类型职责：按 Thread Sequence 游标读取可恢复 Item。
 *
 * @author ethan
 * @date 2026-08-19
 */
@Mapper
public interface AgentItemMapper extends BaseMapper<AgentItemEntity> {

    @Select("SELECT * FROM AGENT_ITEM WHERE THREAD_ID = #{threadId} AND SEQUENCE_NO > #{afterSequence} ORDER BY SEQUENCE_NO LIMIT #{limit}")
    List<AgentItemEntity> selectAfter(String threadId, long afterSequence, int limit);
}
