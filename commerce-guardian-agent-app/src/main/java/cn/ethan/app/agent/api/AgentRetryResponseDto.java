package cn.ethan.app.agent.api;

/**
 * 类型职责：返回人工重试后仍使用原命令和幂等键的确认信息。
 *
 * @author ethan
 * @date 2026-08-19
 */
public record AgentRetryResponseDto(
        String runId,
        String commandId,
        String status,
        String idempotencyKey
) {
}
