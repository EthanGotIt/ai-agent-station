package cn.ethan.core.agent.action;

import java.time.Instant;

/**
 * 类型职责：保存外部动作的幂等结果，使远程成功后本地进程中断仍可安全恢复。
 *
 * @author ethan
 * @date 2026-08-20
 */
public record ExternalActionResultModel(
        String resultId,
        String commandId,
        String idempotencyKey,
        ExternalActionTypeEnum type,
        ExternalActionResultStatusEnum status,
        String responseJson,
        Instant createdAt
) {
    public ExternalActionResultModel {
        if (resultId == null || resultId.isBlank() || commandId == null || commandId.isBlank()
                || idempotencyKey == null || idempotencyKey.isBlank() || type == null || status == null) {
            throw new IllegalArgumentException("External action result identity must not be blank");
        }
        responseJson = responseJson == null ? "{}" : responseJson;
    }
}
