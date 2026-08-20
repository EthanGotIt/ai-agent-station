package cn.ethan.infrastructure.agent.action.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * 类型职责：访问外部动作结果的幂等索引。
 *
 * @author ethan
 * @date 2026-08-20
 */
@Mapper
public interface ExternalActionResultMapper extends BaseMapper<ExternalActionResultEntity> {

    @Select("SELECT * FROM EXTERNAL_ACTION_RESULT WHERE IDEMPOTENCY_KEY = #{idempotencyKey}")
    ExternalActionResultEntity selectByIdempotencyKey(String idempotencyKey);
}
