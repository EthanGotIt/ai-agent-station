package cn.ethan.ai.infrastructure.adapter.port;

import cn.ethan.ai.domain.agent.service.execute.runtime.ToolGuardPolicy;
import com.alibaba.fastjson.JSON;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * ToolCallback 防护包装器：执行前校验本轮授权集合和风险，执行异常统一返回结构化错误。
 */
public class GuardedToolCallback implements ToolCallback {

    private final ToolCallback delegate;

    private final Set<String> allowedToolNames;

    public GuardedToolCallback(ToolCallback delegate, Set<String> allowedToolNames) {
        this.delegate = delegate;
        this.allowedToolNames = allowedToolNames == null ? Set.of() : allowedToolNames.stream()
                .filter(StringUtils::isNotBlank)
                .map(name -> name.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return guardedCall(() -> delegate.call(toolInput));
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return guardedCall(() -> delegate.call(toolInput, toolContext));
    }

    private String guardedCall(Supplier<String> invocation) {
        String toolName = resolveToolName();
        if (StringUtils.isBlank(toolName)) {
            return error("UNKNOWN", "TOOL_NAME_INVALID", "工具名称为空，已拒绝调用。");
        }
        String normalizedToolName = toolName.trim().toLowerCase(Locale.ROOT);
        if (!allowedToolNames.contains(normalizedToolName)) {
            return error(toolName, "TOOL_NOT_AUTHORIZED", "工具不在本轮授权集合内，已拒绝调用。");
        }
        if (ToolGuardPolicy.isBlocked(toolName)) {
            return error(toolName, "TOOL_FORBIDDEN", ToolGuardPolicy.describe(toolName));
        }

        try {
            String result = invocation.get();
            return result == null ? "" : result;
        } catch (IllegalArgumentException e) {
            return error(toolName, "TOOL_ARGUMENT_INVALID", "工具参数错误：" + e.getMessage());
        } catch (Exception e) {
            return error(toolName, "TOOL_CALL_FAILED", "工具调用失败：" + e.getMessage());
        }
    }

    private String resolveToolName() {
        if (delegate == null || delegate.getToolDefinition() == null) {
            return "";
        }
        return delegate.getToolDefinition().name();
    }

    private String error(String toolName, String errorType, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", false);
        payload.put("toolName", toolName);
        payload.put("errorType", errorType);
        payload.put("message", message);
        return JSON.toJSONString(payload);
    }
}
