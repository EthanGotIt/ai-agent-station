package cn.ethan.core.agent.model;

import java.util.Objects;

/**
 * Agent 请求模型：供同步与流式入口共同使用的不可变请求数据。
 *
 * @author ethan
 * @date 2026-08-05
 */
public record AgentRequestModel(
        String requestId,
        String sessionId,
        String message,
        AgentMemoryOptionsModel memory
) {

    public AgentRequestModel(String requestId, String sessionId, String message) {
        this(requestId, sessionId, message, AgentMemoryOptionsModel.DEFAULT);
    }

    public AgentRequestModel {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId is required");
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId is required");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message is required");
        }
        requestId = requestId.trim();
        sessionId = sessionId.trim();
        message = message.trim();
        if (requestId.length() > 128 || sessionId.length() > 128) {
            throw new IllegalArgumentException("requestId and sessionId must not exceed 128");
        }
        if (message.length() > 20_000) {
            throw new IllegalArgumentException("message must not exceed 20000");
        }
        memory = memory == null ? AgentMemoryOptionsModel.DEFAULT : memory;
    }

    public String normalizedMessage() {
        return Objects.requireNonNull(message);
    }

}
