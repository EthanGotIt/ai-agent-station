package cn.ethan.ai.infrastructure.dao;

import cn.ethan.ai.infrastructure.dao.po.RefundCommandPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface RefundCommandMapper {

    RefundCommandPO selectByIdempotencyKeyForUpdate(@Param("idempotencyKey") String idempotencyKey);

    int insertIgnore(RefundCommandPO command);

    int markSuccess(@Param("commandId") String commandId);

    int markFailed(@Param("commandId") String commandId, @Param("failureReason") String failureReason);

    int markPending(@Param("commandId") String commandId);
}
