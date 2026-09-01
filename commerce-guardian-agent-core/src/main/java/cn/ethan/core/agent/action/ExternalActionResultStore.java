package cn.ethan.core.agent.action;

import java.util.Optional;

/**
 * 类型职责：以命令和幂等键唯一保存外部动作结果，支持 Worker 重跑去重。
 *
 * @author ethan
 * @date 2026-08-20
 */
public interface ExternalActionResultStore {

    Optional<ExternalActionResultModel> findByIdempotencyKey(String idempotencyKey);

    ExternalActionResultModel createIfAbsent(ExternalActionResultModel result);
}
