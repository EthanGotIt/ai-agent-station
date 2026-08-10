package cn.ethan.dto;

/**
 * Agent 取消响应 DTO：返回目标请求标识和取消结果。
 *
 * @author ethan
 * @date 2026-08-05
 */
public record AgentCancelResponseDto(String requestId, boolean cancelled) {
}
