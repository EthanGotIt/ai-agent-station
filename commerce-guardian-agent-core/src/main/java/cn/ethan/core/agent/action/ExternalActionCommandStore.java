package cn.ethan.core.agent.action;

import cn.ethan.core.agent.action.ExternalActionCommandModel;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 类型职责：定义外部动作命令的持久化和租约操作。
 *
 * @author ethan
 * @date 2026-08-19
 */
public interface ExternalActionCommandStore {

    ExternalActionCommandModel createIfAbsent(ExternalActionCommandModel command);

    Optional<ExternalActionCommandModel> findById(String userId, String commandId);

    Optional<ExternalActionCommandModel> findByRunId(String userId, String runId);

    Optional<ExternalActionCommandModel> findByIdempotencyKey(String userId, String idempotencyKey);

    List<ExternalActionCommandModel> claimDue(Instant now, Instant leaseUntil, String workerId, int limit);

    void update(ExternalActionCommandModel command);
}
