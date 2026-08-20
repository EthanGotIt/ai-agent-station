package cn.ethan.app.agent.api;

import org.junit.jupiter.api.Test;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * 类型职责：验证异步 SSE 连接结束不会再次进入 JSON 错误响应边界。
 *
 * @author ethan
 * @date 2026-08-21
 */
class AgentExceptionHandlerTest {

    @Test
    void treatsClosedAsyncRequestsAsHandledLifecycleEvents() {
        AgentExceptionHandler handler = new AgentExceptionHandler();

        assertDoesNotThrow(() -> handler.asyncRequestClosed(
                new AsyncRequestNotUsableException("client closed")));
        assertDoesNotThrow(() -> handler.asyncRequestClosed(new AsyncRequestTimeoutException()));
    }
}
