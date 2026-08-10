package cn.ethan.core.agent.model;

import cn.ethan.core.agent.enums.ToolInterventionDecisionEnum;

import java.util.List;

/**
 * 工具人工确认请求：由独立 HTTP 调用提交，不进入会话 FIFO 队列。
 *
 * @author ethan
 * @date 2026-08-09
 */
public record ToolInterventionRequestModel(
        String requestId,
        String sessionId,
        String replyId,
        List<String> toolCallIds,
        ToolInterventionDecisionEnum decision
) {

    public ToolInterventionRequestModel {
        require(requestId, "requestId");
        require(sessionId, "sessionId");
        require(replyId, "replyId");
        if (decision == null || toolCallIds == null || toolCallIds.isEmpty()) {
            throw new IllegalArgumentException("tool intervention request is incomplete");
        }
        toolCallIds = toolCallIds.stream().map(value -> {
            require(value, "toolCallId");
            return value.strip();
        }).distinct().toList();
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException(name + " is invalid");
        }
    }
}
