package cn.ethan.core.agent.model;

import cn.ethan.core.agent.support.CancellationToken;

/**
 * 请求句柄模型：关联请求身份、会话范围和取消令牌。
 *
 * @author ethan
 * @date 2026-08-05
 */
public record RequestHandleModel(
        String requestId,
        String userId,
        String sessionId,
        CancellationToken token
) {
}
