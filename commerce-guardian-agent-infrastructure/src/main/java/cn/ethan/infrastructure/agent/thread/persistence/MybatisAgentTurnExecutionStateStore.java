package cn.ethan.infrastructure.agent.thread.persistence;

import cn.ethan.core.agent.execution.AgentTurnExecutionStateModel;
import cn.ethan.core.agent.execution.AgentTurnExecutionStateStore;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 类型职责：通过单行乐观锁持久化 Turn 恢复位置与累计主动执行时长。
 *
 * @author ethan
 * @date 2026-09-29
 */
@Repository
public class MybatisAgentTurnExecutionStateStore implements AgentTurnExecutionStateStore {

    private final AgentTurnExecutionStateMapper mapper;
    private final JacksonAgentTurnExecutionStateCodec codec;

    public MybatisAgentTurnExecutionStateStore(
            AgentTurnExecutionStateMapper mapper,
            JacksonAgentTurnExecutionStateCodec codec
    ) {
        this.mapper = mapper;
        this.codec = codec;
    }

    @Override
    public Optional<AgentTurnExecutionStateModel> find(String turnId) {
        return Optional.ofNullable(mapper.selectById(turnId)).map(this::toModel);
    }

    @Override
    public void create(AgentTurnExecutionStateModel state) {
        mapper.insert(toEntity(state));
    }

    @Override
    public boolean update(AgentTurnExecutionStateModel expected, AgentTurnExecutionStateModel next) {
        if (!expected.turnId().equals(next.turnId()) || next.version() != expected.version() + 1) {
            throw new IllegalArgumentException("Turn 恢复状态 CAS 身份或版本不连续");
        }
        int updated = mapper.update(null, new UpdateWrapper<AgentTurnExecutionStateEntity>()
                .eq("TURN_ID", expected.turnId())
                .eq("VERSION_NO", expected.version())
                .set("ACTIVE_DURATION_MILLIS", next.activeDurationMillis())
                .set("ACTIVE_TOOL_BATCH_ID", next.activeToolBatchId())
                .set("NEXT_TOOL_INDEX", next.nextToolIndex())
                .set("TOOL_CALLS_JSON", codec.encode(next.toolCalls()))
                .set("UPDATED_AT", next.updatedAt())
                .set("VERSION_NO", next.version()));
        if (updated > 1) {
            throw new IllegalStateException("Turn 恢复状态 CAS 更新了多行：" + expected.turnId());
        }
        return updated == 1;
    }

    private AgentTurnExecutionStateEntity toEntity(AgentTurnExecutionStateModel state) {
        AgentTurnExecutionStateEntity entity = new AgentTurnExecutionStateEntity();
        entity.setTurnId(state.turnId());
        entity.setActiveDurationMillis(state.activeDurationMillis());
        entity.setActiveToolBatchId(state.activeToolBatchId());
        entity.setNextToolIndex(state.nextToolIndex());
        entity.setToolCallsJson(codec.encode(state.toolCalls()));
        entity.setVersionNo(state.version());
        entity.setUpdatedAt(state.updatedAt());
        return entity;
    }

    private AgentTurnExecutionStateModel toModel(AgentTurnExecutionStateEntity entity) {
        return new AgentTurnExecutionStateModel(entity.getTurnId(), value(entity.getActiveDurationMillis()),
                entity.getActiveToolBatchId(), value(entity.getNextToolIndex()), codec.decode(entity.getToolCallsJson()),
                value(entity.getVersionNo()), entity.getUpdatedAt());
    }

    private static long value(Long value) { return value == null ? 0L : value; }
    private static int value(Integer value) { return value == null ? 0 : value; }
}
