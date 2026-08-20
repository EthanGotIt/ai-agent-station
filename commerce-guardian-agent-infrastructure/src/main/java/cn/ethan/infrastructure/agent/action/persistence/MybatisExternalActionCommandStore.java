package cn.ethan.infrastructure.agent.action.persistence;

import cn.ethan.core.agent.action.ExternalActionCommandModel;
import cn.ethan.core.agent.action.ExternalActionCommandStore;
import cn.ethan.infrastructure.agent.action.persistence.ExternalActionCommandEntity;
import cn.ethan.infrastructure.agent.action.persistence.ExternalActionCommandMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 类型职责：通过唯一幂等键和条件更新保证外部动作命令只写入一次。
 *
 * @author ethan
 * @date 2026-08-19
 */
@Repository
public final class MybatisExternalActionCommandStore implements ExternalActionCommandStore {

    private final ExternalActionCommandMapper mapper;

    public MybatisExternalActionCommandStore(ExternalActionCommandMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public ExternalActionCommandModel createIfAbsent(ExternalActionCommandModel command) {
        ExternalActionCommandEntity existing = mapper.selectByIdempotencyKey(command.userId(), command.idempotencyKey());
        if (existing != null) {
            return toModel(existing);
        }
        try {
            mapper.insert(toEntity(command));
            return command;
        } catch (DuplicateKeyException duplicate) {
            ExternalActionCommandEntity raced = mapper.selectByIdempotencyKey(command.userId(), command.idempotencyKey());
            if (raced == null) {
                throw duplicate;
            }
            return toModel(raced);
        }
    }

    @Override
    public Optional<ExternalActionCommandModel> findById(String userId, String commandId) {
        return Optional.ofNullable(mapper.selectOne(new QueryWrapper<ExternalActionCommandEntity>()
                        .eq("USER_ID", userId).eq("COMMAND_ID", commandId)))
                .map(this::toModel);
    }

    @Override
    public Optional<ExternalActionCommandModel> findByRunId(String userId, String runId) {
        return Optional.ofNullable(mapper.selectByRunId(userId, runId)).map(this::toModel);
    }

    @Override
    public Optional<ExternalActionCommandModel> findByIdempotencyKey(String userId, String idempotencyKey) {
        return Optional.ofNullable(mapper.selectByIdempotencyKey(userId, idempotencyKey)).map(this::toModel);
    }

    @Override
    @Transactional
    public List<ExternalActionCommandModel> claimDue(Instant now, Instant leaseUntil, String workerId, int limit) {
        return mapper.selectDue(now, Math.max(1, Math.min(limit, 100))).stream()
                .map(entity -> {
                    ExternalActionCommandModel claimed = toModel(entity).claimed(workerId, leaseUntil, now);
                    int updated = mapper.update(toEntity(claimed), new UpdateWrapper<ExternalActionCommandEntity>()
                            .eq("COMMAND_ID", entity.getCommandId())
                            .eq("STATUS", entity.getStatus())
                            .and(wrapper -> wrapper.isNull("LEASE_UNTIL").or().lt("LEASE_UNTIL", now)));
                    return updated == 1 ? claimed : null;
                }).filter(java.util.Objects::nonNull).toList();
    }

    @Override
    public void update(ExternalActionCommandModel command) {
        mapper.update(toEntity(command), new UpdateWrapper<ExternalActionCommandEntity>()
                .eq("COMMAND_ID", command.commandId())
                .eq("USER_ID", command.userId()));
    }

    private ExternalActionCommandEntity toEntity(ExternalActionCommandModel model) {
        ExternalActionCommandEntity entity = new ExternalActionCommandEntity();
        entity.setCommandId(model.commandId());
        entity.setRunId(model.runId());
        entity.setThreadId(model.threadId());
        entity.setTurnId(model.turnId());
        entity.setUserId(model.userId());
        entity.setActionType(model.type());
        entity.setIdempotencyKey(model.idempotencyKey());
        entity.setPayloadJson(model.payloadJson());
        entity.setStatus(model.status());
        entity.setAttemptCount(model.attemptCount());
        entity.setMaxAttempts(model.maxAttempts());
        entity.setNextAttemptAt(model.nextAttemptAt());
        entity.setLeaseOwner(model.leaseOwner());
        entity.setLeaseUntil(model.leaseUntil());
        entity.setLastErrorCode(model.lastErrorCode());
        entity.setLastErrorMessage(model.lastErrorMessage());
        entity.setCreatedAt(model.createdAt());
        entity.setUpdatedAt(model.updatedAt());
        entity.setCompletedAt(model.completedAt());
        return entity;
    }

    private ExternalActionCommandModel toModel(ExternalActionCommandEntity entity) {
        return new ExternalActionCommandModel(entity.getCommandId(), entity.getRunId(), entity.getThreadId(),
                entity.getTurnId(), entity.getUserId(), entity.getActionType(), entity.getIdempotencyKey(),
                entity.getPayloadJson(), entity.getStatus(), value(entity.getAttemptCount()), value(entity.getMaxAttempts()),
                entity.getNextAttemptAt(), entity.getLeaseOwner(), entity.getLeaseUntil(), entity.getLastErrorCode(),
                entity.getLastErrorMessage(), entity.getCreatedAt(), entity.getUpdatedAt(), entity.getCompletedAt());
    }

    private int value(Integer value) { return value == null ? 0 : value; }
}
