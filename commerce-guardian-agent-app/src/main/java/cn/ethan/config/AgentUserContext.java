package cn.ethan.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * 类型职责：在 HTTP 边界解析演示身份，避免 Controller 直接信任请求体身份。
 *
 * @author ethan
 * @date 2026-08-19
 */
@Component
public final class AgentUserContext {

    public String currentUserId(HttpServletRequest request) {
        String userId = request.getHeader("X-User-Id");
        if (userId == null || userId.isBlank() || userId.length() > 256) {
            throw new IllegalArgumentException("缺少有效的用户身份上下文");
        }
        return userId.trim();
    }
}
