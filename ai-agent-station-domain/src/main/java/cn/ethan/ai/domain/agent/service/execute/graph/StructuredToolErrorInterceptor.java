package cn.ethan.ai.domain.agent.service.execute.graph;

import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import com.alibaba.fastjson.JSON;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 工具异常统一归一化。重试拦截器应位于本拦截器内层，确保瞬态异常先重试再返回结构化结果。
 */
public class StructuredToolErrorInterceptor extends ToolInterceptor {

    private static final Pattern SENSITIVE_VALUE_PATTERN = Pattern.compile(
            "(?i)(api[-_ ]?key|authorization|token|secret|password)(\\s*[:=]\\s*|\\\"\\s*:\\s*\\\")([^,;\\s\\\"]+)"
    );

    private static final Pattern BEARER_PATTERN = Pattern.compile("(?i)bearer\\s+\\S+");

    @Override
    public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
        try {
            return handler.call(request);
        } catch (Exception e) {
            Throwable root = rootCause(e);
            String errorType = resolveErrorType(root);
            String message = sanitize(root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage());
            return ToolCallResponse.of(
                    request.getToolCallId(),
                    request.getToolName(),
                    error(request.getToolName(), errorType, message)
            );
        }
    }

    @Override
    public String getName() {
        return "StructuredToolError";
    }

    private String resolveErrorType(Throwable throwable) {
        if (throwable instanceof ToolGuardException guardException) {
            return guardException.getErrorType();
        }
        if (throwable instanceof IllegalArgumentException) {
            return "TOOL_ARGUMENT_INVALID";
        }
        return "TOOL_CALL_FAILED";
    }

    private Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private String error(String toolName, String errorType, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", false);
        payload.put("toolName", toolName);
        payload.put("errorType", errorType);
        payload.put("message", message);
        return JSON.toJSONString(payload);
    }

    private String sanitize(String message) {
        String sanitized = BEARER_PATTERN.matcher(message).replaceAll("Bearer ***");
        return SENSITIVE_VALUE_PATTERN.matcher(sanitized).replaceAll("$1$2***");
    }
}
