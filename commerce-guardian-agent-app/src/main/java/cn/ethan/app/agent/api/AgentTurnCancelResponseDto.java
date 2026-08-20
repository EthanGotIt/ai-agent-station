package cn.ethan.app.agent.api;

/**
 * 类型职责：表达 Turn 取消请求的实际收敛结果。
 *
 * @author ethan
 * @date 2026-08-19
 */
public record AgentTurnCancelResponseDto(String turnId, boolean cancelled) {
}
