package cn.ethan.infrastructure.agent.thread.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 类型职责：访问 Turn 的持久化恢复游标。
 *
 * @author ethan
 * @date 2026-09-29
 */
@Mapper
public interface AgentTurnExecutionStateMapper extends BaseMapper<AgentTurnExecutionStateEntity> {
}
