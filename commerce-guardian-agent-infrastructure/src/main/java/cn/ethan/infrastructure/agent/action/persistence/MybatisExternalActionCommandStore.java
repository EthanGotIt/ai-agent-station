package cn.ethan.infrastructure.agent.action.persistence;

import cn.ethan.core.agent.action.ExternalActionCommandModel;
import cn.ethan.core.agent.action.ExternalActionCommandStore;
import cn.ethan.core.agent.action.ExternalActionStatusEnum;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 类型职责：通过唯一幂等键和条件更新保证外部动作命令只写入一次。
 * 该适配器需要保留可代理性，以承接 Spring 的异常翻译和事务边界。
 *
 * @author ethan
 * @date 2026-08-19
 */
@Repository
public class MybatisExternalActionCommandStore implements ExternalActionCommandStore {

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
                    UpdateWrapper<ExternalActionCommandEntity> wrapper = new UpdateWrapper<ExternalActionCommandEntity>()
                            .eq("COMMAND_ID", entity.getCommandId())
                            .eq("USER_ID", entity.getUserId())
                            .eq("STATUS", entity.getStatus().name())
                            .eq("VERSION_NO", value(entity.getVersionNo()))
                            .eq("ATTEMPT_COUNT", value(entity.getAttemptCount()))
                            .eq("RETRY_CYCLE_ATTEMPT_COUNT", value(entity.getRetryCycleAttemptCount()))
                            .and(value -> value.isNull("LEASE_UNTIL").or().le("LEASE_UNTIL", now));
                    setState(wrapper, claimed);
                    int updated = mapper.update(null, wrapper);
                    return updated == 1 ? claimed : null;
                }).filter(java.util.Objects::nonNull).toList();
    }

    @Override
    public boolean update(ExternalActionCommandModel expected, ExternalActionCommandModel next) {
        if (!validTransition(expected, next)) {
            return false;
        }
        UpdateWrapper<ExternalActionCommandEntity> wrapper = new UpdateWrapper<ExternalActionCommandEntity>()
                .eq("COMMAND_ID", expected.commandId())
                .eq("USER_ID", expected.userId())
                .eq("VERSION_NO", expected.version())
                .eq("STATUS", expected.status().name())
                .eq("ATTEMPT_COUNT", expected.attemptCount())
                .eq("RETRY_CYCLE_ATTEMPT_COUNT", expected.retryCycleAttemptCount());
        if (expected.status() == ExternalActionStatusEnum.PROCESSING) {
            wrapper.eq("LEASE_OWNER", expected.leaseOwner())
                    .eq("LEASE_UNTIL", expected.leaseUntil());
        } else {
            wrapper.isNull("LEASE_OWNER").isNull("LEASE_UNTIL");
        }
        // 失败恢复和成功收敛需要清除旧租约、下一次执行时间及错误字段。
        setState(wrapper, next);
        return mapper.update(null, wrapper) == 1;
    }

    private boolean validTransition(ExternalActionCommandModel expected, ExternalActionCommandModel next) {
        if (expected == null || next == null || next.version() != expected.version() + 1
                || !expected.commandId().equals(next.commandId())
                || !expected.userId().equals(next.userId())
                || !expected.runId().equals(next.runId())
                || !expected.threadId().equals(next.threadId())
                || !Objects.equals(expected.turnId(), next.turnId())
                || expected.type() != next.type()
                || !expected.idempotencyKey().equals(next.idempotencyKey())
                || !expected.payloadJson().equals(next.payloadJson())
                || !expected.createdAt().equals(next.createdAt())
                || expected.attemptCount() != next.attemptCount()
                || expected.maxAttempts() != next.maxAttempts()) {
            return false;
        }
        if (expected.status() == ExternalActionStatusEnum.PROCESSING) {
            return next.retryCycleAttemptCount() == expected.retryCycleAttemptCount()
                    && (next.status() == ExternalActionStatusEnum.SUCCEEDED
                    || next.status() == ExternalActionStatusEnum.RETRY_WAIT
                    || next.status() == ExternalActionStatusEnum.MANUAL_RETRY_REQUIRED);
        }
        return expected.status() == ExternalActionStatusEnum.MANUAL_RETRY_REQUIRED
                && next.status() == ExternalActionStatusEnum.PENDING
                && next.retryCycleAttemptCount() == 0;
    }

    private void setState(UpdateWrapper<ExternalActionCommandEntity> wrapper,
                          ExternalActionCommandModel command) {
        wrapper.set("STATUS", command.status().name())
                .set("VERSION_NO", command.version())
                .set("ATTEMPT_COUNT", command.attemptCount())
                .set("MAX_ATTEMPTS", command.maxAttempts())
                .set("RETRY_CYCLE_ATTEMPT_COUNT", command.retryCycleAttemptCount())
                .set("NEXT_ATTEMPT_AT", command.nextAttemptAt())
                .set("LEASE_OWNER", command.leaseOwner())
                .set("LEASE_UNTIL", command.leaseUntil())
                .set("LAST_ERROR_CODE", command.lastErrorCode())
                .set("LAST_ERROR_MESSAGE", command.lastErrorMessage())
                .set("UPDATED_AT", command.updatedAt())
                .set("COMPLETED_AT", command.completedAt());
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
        entity.setVersionNo(model.version());
        entity.setAttemptCount(model.attemptCount());
        entity.setMaxAttempts(model.maxAttempts());
        entity.setRetryCycleAttemptCount(model.retryCycleAttemptCount());
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
                entity.getLastErrorMessage(), entity.getCreatedAt(), entity.getUpdatedAt(), entity.getCompletedAt(),
                value(entity.getVersionNo()), value(entity.getRetryCycleAttemptCount()));
    }

    private int value(Integer value) { return value == null ? 0 : value; }

    private long value(Long value) { return value == null ? 0L : value; }
}
