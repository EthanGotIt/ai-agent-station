package cn.ethan.infrastructure.agent.thread.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
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

    @Update("UPDATE AGENT_THREAD SET OPEN_QUESTION_ID = #{questionId}, UPDATED_AT = #{updatedAt} "
            + "WHERE THREAD_ID = #{threadId} AND USER_ID = #{userId} AND OPEN_QUESTION_ID IS NULL")
    int setOpenQuestion(String threadId, String userId, String questionId, Instant updatedAt);

    @Update("UPDATE AGENT_THREAD SET OPEN_INTERACTION_TYPE = #{interactionType}, "
            + "OPEN_INTERACTION_ID = #{interactionId}, "
            + "OPEN_QUESTION_ID = CASE WHEN #{interactionType} = 'QUESTION_CARD' THEN #{interactionId} ELSE NULL END, "
            + "UPDATED_AT = #{updatedAt} WHERE THREAD_ID = #{threadId} AND USER_ID = #{userId} "
            + "AND OPEN_INTERACTION_ID IS NULL")
    int setOpenInteraction(String threadId, String userId, String interactionType,
                           String interactionId, Instant updatedAt);

    @Update("UPDATE AGENT_THREAD SET OPEN_QUESTION_ID = NULL, UPDATED_AT = #{updatedAt} "
            + "WHERE THREAD_ID = #{threadId} AND USER_ID = #{userId} AND OPEN_QUESTION_ID = #{questionId}")
    int clearOpenQuestion(String threadId, String userId, String questionId, Instant updatedAt);

    @Update("UPDATE AGENT_THREAD SET OPEN_INTERACTION_TYPE = NULL, OPEN_INTERACTION_ID = NULL, "
            + "OPEN_QUESTION_ID = NULL, UPDATED_AT = #{updatedAt} WHERE THREAD_ID = #{threadId} "
            + "AND USER_ID = #{userId} AND OPEN_INTERACTION_TYPE = #{interactionType} "
            + "AND OPEN_INTERACTION_ID = #{interactionId}")
    int clearOpenInteraction(String threadId, String userId, String interactionType,
                              String interactionId, Instant updatedAt);

    @Select("SELECT * FROM AGENT_THREAD WHERE USER_ID = #{userId} ORDER BY UPDATED_AT DESC")
    List<AgentThreadEntity> selectByUser(String userId);

    @Select("SELECT * FROM AGENT_THREAD WHERE USER_ID = #{userId} ORDER BY UPDATED_AT DESC LIMIT #{limit} OFFSET #{offset}")
    List<AgentThreadEntity> selectPage(String userId, int offset, int limit);

    @Select("SELECT * FROM AGENT_THREAD WHERE USER_ID = #{userId} AND STATUS = #{status} ORDER BY UPDATED_AT DESC LIMIT #{limit} OFFSET #{offset}")
    List<AgentThreadEntity> selectPageByStatus(String userId, String status, int offset, int limit);

    @Select("SELECT COUNT(*) FROM AGENT_THREAD WHERE USER_ID = #{userId}")
    long countByUser(String userId);

    @Select("SELECT COUNT(*) FROM AGENT_THREAD WHERE USER_ID = #{userId} AND STATUS = #{status}")
    long countByUserAndStatus(String userId, String status);
}
