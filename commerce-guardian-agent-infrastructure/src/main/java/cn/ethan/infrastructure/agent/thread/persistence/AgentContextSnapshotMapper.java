package cn.ethan.infrastructure.agent.thread.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 类型职责：读取和保存 Thread 的最新上下文摘要快照。
 *
 * @author ethan
 * @date 2026-08-19
 */
@Mapper
public interface AgentContextSnapshotMapper extends BaseMapper<AgentContextSnapshotEntity> {

    @Select("SELECT SNAPSHOT_ID, THREAD_ID, THROUGH_SEQUENCE, VERSION_NO, ESTIMATED_TOKENS, SUMMARY, CREATED_AT, "
            + "FORMAT_VERSION, BASE_SNAPSHOT_ID, SOURCE_FROM_SEQUENCE, SOURCE_ESTIMATED_TOKENS, "
            + "SUMMARY_PROMPT_VERSION AS PROMPT_VERSION, SUMMARY_MAX_OUTPUT_TOKENS "
            + "FROM AGENT_CONTEXT_SNAPSHOT WHERE THREAD_ID = #{threadId} "
            + "ORDER BY VERSION_NO DESC, CREATED_AT DESC LIMIT 1")
    AgentContextSnapshotEntity selectLatest(String threadId);

    @Select("SELECT SNAPSHOT_ID, THREAD_ID, THROUGH_SEQUENCE, VERSION_NO, ESTIMATED_TOKENS, SUMMARY, CREATED_AT, "
            + "FORMAT_VERSION, BASE_SNAPSHOT_ID, SOURCE_FROM_SEQUENCE, SOURCE_ESTIMATED_TOKENS, "
            + "SUMMARY_PROMPT_VERSION AS PROMPT_VERSION, SUMMARY_MAX_OUTPUT_TOKENS "
            + "FROM AGENT_CONTEXT_SNAPSHOT WHERE THREAD_ID = #{threadId} "
            + "AND SNAPSHOT_ID = #{snapshotId} LIMIT 1")
    AgentContextSnapshotEntity selectBySnapshotId(
            @Param("threadId") String threadId,
            @Param("snapshotId") String snapshotId
    );
}
