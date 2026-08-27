package cn.ethan.infrastructure.agent.thread.persistence;

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

    @Select("SELECT * FROM AGENT_TURN WHERE USER_ID = #{userId} AND CLIENT_REQUEST_ID = #{clientRequestId} FOR UPDATE")
    AgentTurnEntity selectByRequestForUpdate(String userId, String clientRequestId);

    @Select("SELECT * FROM AGENT_TURN WHERE STATUS IN ('QUEUED', 'ACTIVE') ORDER BY CREATED_AT")
    List<AgentTurnEntity> selectRecoverable();

    @Select("SELECT COUNT(*) FROM AGENT_TURN WHERE USER_ID = #{userId} AND THREAD_ID = #{threadId} AND STATUS IN ('QUEUED', 'ACTIVE')")
    long countActiveByThread(String userId, String threadId);

    @Select("""
            SELECT T.TURN_ID, T.USER_ID, T.WORKFLOW_RUN_ID,
                   R.STATUS AS WORKFLOW_RUN_STATUS,
                   CASE WHEN EXISTS (
                       SELECT 1
                       FROM AGENT_QUESTION_CARD Q
                       WHERE Q.RUN_ID = R.RUN_ID AND Q.STATUS = 'OPEN'
                   ) OR EXISTS (
                       SELECT 1
                       FROM AGENT_WORKFLOW_CHECKPOINT C
                       WHERE C.RUN_ID = R.RUN_ID AND C.STATUS = 'OPEN'
                   ) THEN 1 ELSE 0 END AS OPEN_INTERACTION
            FROM AGENT_TURN T
            JOIN AGENT_WORKFLOW_RUN R ON R.TURN_ID = T.TURN_ID
            WHERE T.STATUS = 'WAITING_USER_INPUT'
              AND (R.STATUS <> 'WAITING_USER_INPUT' OR NOT EXISTS (
                  SELECT 1 FROM AGENT_QUESTION_CARD Q
                  WHERE Q.RUN_ID = R.RUN_ID AND Q.STATUS = 'OPEN'
              ) AND NOT EXISTS (
                  SELECT 1 FROM AGENT_WORKFLOW_CHECKPOINT C
                  WHERE C.RUN_ID = R.RUN_ID AND C.STATUS = 'OPEN'
              ))
            ORDER BY T.CREATED_AT
            """)
    List<AgentWorkflowOwnerRecoveryRow> selectWorkflowOwnerRecoveryCandidates();
}
