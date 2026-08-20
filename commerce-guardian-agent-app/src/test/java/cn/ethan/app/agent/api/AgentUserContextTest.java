package cn.ethan.app.agent.api;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 类型职责：验证演示认证 Header 与用户身份数据库列使用同一规范化边界。
 *
 * @author ethan
 * @date 2026-08-21
 */
class AgentUserContextTest {

    @Test
    void trimsAndAccepts128CharacterUserId() {
        String userId = "u".repeat(128);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", "  " + userId + "  ");

        assertEquals(userId, new AgentUserContext().currentUserId(request));
    }

    @Test
    void rejectsUserIdBeyondDatabaseBoundary() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", "u".repeat(129));

        assertThrows(IllegalArgumentException.class,
                () -> new AgentUserContext().currentUserId(request));
    }
}
