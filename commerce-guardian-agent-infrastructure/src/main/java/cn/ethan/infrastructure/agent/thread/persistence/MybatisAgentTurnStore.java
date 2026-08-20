package cn.ethan.infrastructure.agent.thread.persistence;

import cn.ethan.core.agent.thread.AgentTurnModel;
import cn.ethan.core.agent.thread.AgentTurnStatusEnum;
import cn.ethan.core.agent.thread.AgentTurnStore;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 类型职责：只持久化 Turn 生命周期、请求幂等和重启恢复状态。
 *
 * @author ethan
 * @date 2026-08-20
 */
@Repository
public final class MybatisAgentTurnStore implements AgentTurnStore {

    private final AgentTurnMapper mapper;

    public MybatisAgentTurnStore(AgentTurnMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<AgentTurnModel> findTurn(String userId, String turnId) {
        return Optional.ofNullable(mapper.selectOne(new QueryWrapper<AgentTurnEntity>()
                        .eq("TURN_ID", turnId).eq("USER_ID", userId)))
                .map(MybatisAgentTurnStore::toModel);
    }

    @Override
    public Optional<AgentTurnModel> findTurnByRequest(String userId, String clientRequestId) {
        return Optional.ofNullable(mapper.selectByRequest(userId, clientRequestId)).map(MybatisAgentTurnStore::toModel);
    }

    @Override
    public void createTurn(AgentTurnModel turn) {
        mapper.insert(toEntity(turn));
    }

    @Override
    public void updateTurn(AgentTurnModel turn) {
        mapper.update(toEntity(turn), new UpdateWrapper<AgentTurnEntity>()
                .eq("TURN_ID", turn.turnId())
                .eq("USER_ID", turn.userId()));
    }

    @Override
    public List<AgentTurnModel> listRecoverableTurns() {
        return mapper.selectRecoverable().stream().map(MybatisAgentTurnStore::toModel).toList();
    }

    private static AgentTurnEntity toEntity(AgentTurnModel model) {
        AgentTurnEntity entity = new AgentTurnEntity();
        entity.setTurnId(model.turnId());
        entity.setThreadId(model.threadId());
        entity.setUserId(model.userId());
        entity.setClientRequestId(model.clientRequestId());
        entity.setInputText(model.input());
        entity.setStatus(model.status().name());
        entity.setQueuePosition(model.queuePosition());
        entity.setWorkflowRunId(model.workflowRunId());
        entity.setErrorCode(model.errorCode());
        entity.setCreatedAt(model.createdAt());
        entity.setStartedAt(model.startedAt());
        entity.setFinishedAt(model.finishedAt());
        return entity;
    }

    private static AgentTurnModel toModel(AgentTurnEntity entity) {
        return new AgentTurnModel(entity.getTurnId(), entity.getThreadId(), entity.getUserId(),
                entity.getClientRequestId(), entity.getInputText(), AgentTurnStatusEnum.valueOf(entity.getStatus()),
                value(entity.getQueuePosition()), entity.getWorkflowRunId(), entity.getErrorCode(), entity.getCreatedAt(),
                entity.getStartedAt(), entity.getFinishedAt());
    }

    private static int value(Integer value) {
        return value == null ? 0 : value;
    }
}
