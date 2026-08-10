package cn.ethan.dto;

/**
 * ReAct 工具确认响应 DTO：只表示决定是否成功送达活跃回合。
 *
 * @author ethan
 * @date 2026-08-09
 */
public record AgentToolInterventionResponseDto(String requestId, String replyId, boolean accepted) {
}
