package cn.ethan.app.agent.api;

/**
 * Agent 错误响应 DTO：统一描述接口错误码、提示和冲突请求标识。
 *
 * @author ethan
 * @date 2026-08-05
 */
public record AgentErrorResponseDto(String code, String message, String relatedRequestId) {
}
