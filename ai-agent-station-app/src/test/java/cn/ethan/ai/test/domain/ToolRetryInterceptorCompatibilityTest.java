package cn.ethan.ai.test.domain;

import cn.ethan.ai.domain.agent.service.execute.graph.StructuredToolErrorInterceptor;
import cn.ethan.ai.domain.agent.service.execute.graph.ToolGuardException;
import cn.ethan.ai.domain.agent.service.execute.graph.ToolGuardPolicy;
import com.alibaba.cloud.ai.graph.agent.interceptor.InterceptorChain;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.toolretry.ToolRetryInterceptor;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public class ToolRetryInterceptorCompatibilityTest {

    @Test
    public void shouldRetryQueryToolOnceThenReturnSuccess() {
        AtomicInteger attempts = new AtomicInteger();
        ToolCallHandler handler = chain(Set.of("web_search_exa"), request -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("temporary failure");
            }
            return ToolCallResponse.of(request.getToolCallId(), request.getToolName(), "ok");
        });

        ToolCallResponse response = handler.call(request("web_search_exa"));

        Assert.assertEquals("ok", response.getResult());
        Assert.assertEquals(2, attempts.get());
    }

    @Test
    public void shouldReturnStructuredErrorAfterQueryRetryIsExhausted() {
        AtomicInteger attempts = new AtomicInteger();
        ToolCallHandler handler = chain(Set.of("web_search_exa"), request -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("still unavailable");
        });

        ToolCallResponse response = handler.call(request("web_search_exa"));

        Assert.assertTrue(response.getResult().contains("\"success\":false"));
        Assert.assertTrue(response.getResult().contains("\"errorType\":\"TOOL_CALL_FAILED\""));
        Assert.assertEquals(2, attempts.get());
    }

    @Test
    public void shouldNotRetryInvalidArguments() {
        AtomicInteger attempts = new AtomicInteger();
        ToolCallHandler handler = chain(Set.of("web_search_exa"), request -> {
            attempts.incrementAndGet();
            throw new IllegalArgumentException("query is required");
        });

        ToolCallResponse response = handler.call(request("web_search_exa"));

        Assert.assertTrue(response.getResult().contains("\"errorType\":\"TOOL_ARGUMENT_INVALID\""));
        Assert.assertEquals(1, attempts.get());
    }

    @Test
    public void shouldNotRetryWriteTool() {
        AtomicInteger attempts = new AtomicInteger();
        ToolCallHandler handler = chain(Set.of("web_search_exa"), request -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("notify unavailable");
        });

        ToolCallResponse response = handler.call(request("send_notification"));

        Assert.assertTrue(response.getResult().contains("\"errorType\":\"TOOL_CALL_FAILED\""));
        Assert.assertEquals(1, attempts.get());
        Assert.assertTrue(ToolGuardPolicy.isRetryable("web_search_exa"));
        Assert.assertFalse(ToolGuardPolicy.isRetryable("send_notification"));
    }

    @Test
    public void shouldReturnSanitizedGuardErrorWithoutRetry() {
        AtomicInteger attempts = new AtomicInteger();
        ToolCallHandler handler = chain(Set.of("web_search_exa"), request -> {
            attempts.incrementAndGet();
            throw new ToolGuardException("TOOL_NOT_AUTHORIZED", "authorization=Bearer secret-value");
        });

        ToolCallResponse response = handler.call(request("web_search_exa"));

        Assert.assertTrue(response.getResult().contains("\"errorType\":\"TOOL_NOT_AUTHORIZED\""));
        Assert.assertFalse(response.getResult().contains("secret-value"));
        Assert.assertEquals(1, attempts.get());
    }

    private ToolCallHandler chain(Set<String> retryableToolNames, ToolCallHandler terminalHandler) {
        List<ToolInterceptor> interceptors = List.of(
                new StructuredToolErrorInterceptor(),
                ToolRetryInterceptor.builder()
                        .toolNames(retryableToolNames)
                        .maxRetries(1)
                        .initialDelay(0)
                        .maxDelay(0)
                        .jitter(false)
                        .retryOn(exception -> !(exception instanceof IllegalArgumentException)
                                && !(exception instanceof ToolGuardException))
                        .onFailure(ToolRetryInterceptor.OnFailureBehavior.RAISE)
                        .build()
        );
        return InterceptorChain.chainToolInterceptors(interceptors, terminalHandler);
    }

    private ToolCallRequest request(String toolName) {
        return ToolCallRequest.builder()
                .toolCallId("call-1")
                .toolName(toolName)
                .arguments("{}")
                .build();
    }
}
