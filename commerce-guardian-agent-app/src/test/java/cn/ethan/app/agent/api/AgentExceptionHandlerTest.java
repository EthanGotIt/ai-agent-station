package cn.ethan.app.agent.api;

import org.junit.jupiter.api.Test;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.lang.reflect.Constructor;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void mapsQueryParameterConversionFailureToBadRequest() throws ReflectiveOperationException {
        AgentExceptionHandler handler = new AgentExceptionHandler();
        MethodArgumentTypeMismatchException failure = newQueryParameterConversionFailure();

        assertEquals(400, handler.invalidParameter(failure).getStatusCode().value());
        assertEquals("INVALID_REQUEST", handler.invalidParameter(failure).getBody().code());
    }

    private MethodArgumentTypeMismatchException newQueryParameterConversionFailure()
            throws ReflectiveOperationException {
        Constructor<MethodArgumentTypeMismatchException> constructor =
                MethodArgumentTypeMismatchException.class.getConstructor(
                        Object.class, Class.class, String.class,
                        Class.forName("org.springframework.core.MethodParameter"), Throwable.class);
        return constructor.newInstance("not-a-number", Integer.class, "page", null,
                new NumberFormatException());
    }
}
