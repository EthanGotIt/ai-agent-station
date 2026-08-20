package cn.ethan.infrastructure.agent.action.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;

/**
 * 类型职责：访问外部动作命令并按 Lease 原子领取待执行任务。
 *
 * @author ethan
 * @date 2026-08-19
 */
@Mapper
public interface ExternalActionCommandMapper extends BaseMapper<ExternalActionCommandEntity> {

    @Select("SELECT * FROM EXTERNAL_ACTION_COMMAND WHERE USER_ID = #{userId} AND IDEMPOTENCY_KEY = #{idempotencyKey}")
    ExternalActionCommandEntity selectByIdempotencyKey(String userId, String idempotencyKey);

    @Select("SELECT * FROM EXTERNAL_ACTION_COMMAND WHERE USER_ID = #{userId} AND RUN_ID = #{runId} ORDER BY CREATED_AT DESC LIMIT 1")
    ExternalActionCommandEntity selectByRunId(String userId, String runId);

    @Select("SELECT * FROM EXTERNAL_ACTION_COMMAND WHERE ((STATUS IN ('PENDING', 'RETRY_WAIT') AND NEXT_ATTEMPT_AT IS NOT NULL AND NEXT_ATTEMPT_AT <= #{now}) OR (STATUS = 'PROCESSING' AND LEASE_UNTIL IS NOT NULL AND LEASE_UNTIL <= #{now})) AND (LEASE_UNTIL IS NULL OR LEASE_UNTIL <= #{now}) ORDER BY CREATED_AT LIMIT #{limit}")
    List<ExternalActionCommandEntity> selectDue(Instant now, int limit);
}
