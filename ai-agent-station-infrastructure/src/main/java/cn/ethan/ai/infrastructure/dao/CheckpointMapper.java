package cn.ethan.ai.infrastructure.dao;

import cn.ethan.ai.infrastructure.dao.po.CheckpointPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Optional;

@Mapper
public interface CheckpointMapper {

    int insert(CheckpointPO checkpoint);

    Optional<CheckpointPO> selectById(@Param("checkpointId") String checkpointId);
}
