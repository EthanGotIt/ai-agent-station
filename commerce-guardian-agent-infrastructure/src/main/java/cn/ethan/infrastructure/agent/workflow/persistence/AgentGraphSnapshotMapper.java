package cn.ethan.infrastructure.agent.workflow.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 类型职责：按 graph thread 读取和保存 LangGraph 技术快照。
 *
 * @author ethan
 * @date 2026-08-27
 */
@Mapper
public interface AgentGraphSnapshotMapper extends BaseMapper<AgentGraphSnapshotEntity> {

    @Select("SELECT * FROM AGENT_GRAPH_SNAPSHOT WHERE GRAPH_THREAD_ID = #{graphThreadId} "
            + "ORDER BY UPDATED_AT DESC, SNAPSHOT_ID DESC")
    List<AgentGraphSnapshotEntity> selectByGraphThreadId(String graphThreadId);

    @Select("SELECT * FROM AGENT_GRAPH_SNAPSHOT WHERE GRAPH_THREAD_ID = #{graphThreadId} "
            + "AND CHECKPOINT_ID = #{checkpointId}")
    AgentGraphSnapshotEntity selectByGraphThreadAndCheckpoint(String graphThreadId, String checkpointId);
}
