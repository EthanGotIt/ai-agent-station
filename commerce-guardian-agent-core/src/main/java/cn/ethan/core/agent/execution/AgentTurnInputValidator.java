package cn.ethan.core.agent.execution;

import cn.ethan.core.agent.thread.AgentThreadModel;
import cn.ethan.core.agent.thread.AgentTurnModel;

/**
 * 类型职责：集中校验进入 Runtime 的身份、请求 ID、消息和结构化回答，避免生命周期类混入协议清洗。
 *
 * @author ethan
 * @date 2026-08-24
 */
public final class AgentTurnInputValidator {

    private AgentTurnInputValidator() {
    }

    public static String requireText(String value, String name) {
        String normalized = value == null ? null : value.trim();
        if (normalized == null || normalized.isBlank()
                || normalized.length() > AgentTurnModel.MAX_USER_MESSAGE_LENGTH) {
            throw new IllegalArgumentException(name + " 不能为空且长度不能超过 "
                    + AgentTurnModel.MAX_USER_MESSAGE_LENGTH);
        }
        return normalized;
    }

    public static String requireClientRequestId(String clientRequestId) {
        String normalized = clientRequestId == null ? null : clientRequestId.trim();
        if (normalized == null || normalized.isBlank()
                || normalized.length() > AgentTurnModel.MAX_CLIENT_REQUEST_ID_LENGTH) {
            throw new IllegalArgumentException("clientRequestId 不能为空且长度不能超过 "
                    + AgentTurnModel.MAX_CLIENT_REQUEST_ID_LENGTH);
        }
        return normalized;
    }

    public static String normalizeUserId(String userId) {
        String normalized = userId == null ? null : userId.trim();
        if (normalized == null || normalized.isBlank()
                || normalized.length() > AgentThreadModel.MAX_USER_ID_LENGTH) {
            throw new IllegalArgumentException("userId 不能为空且长度不能超过 "
                    + AgentThreadModel.MAX_USER_ID_LENGTH);
        }
        return normalized;
    }

}
