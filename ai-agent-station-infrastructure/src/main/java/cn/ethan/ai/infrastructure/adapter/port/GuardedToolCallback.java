package cn.ethan.ai.infrastructure.adapter.port;

import cn.ethan.ai.domain.agent.service.execute.graph.ToolGuardPolicy;
import cn.ethan.ai.domain.agent.service.execute.graph.ToolGuardException;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * ToolCallback 防护包装器：执行前校验本轮授权集合和风险。
 */
public class GuardedToolCallback implements ToolCallback {

    private final ToolCallback delegate;

    private final Set<String> allowedToolNames;

    public GuardedToolCallback(ToolCallback delegate, Set<String> allowedToolNames) {
        this.delegate = delegate;
        this.allowedToolNames = allowedToolNames == null ? Set.of() : allowedToolNames.stream()
                .filter(StringUtils::isNotBlank)
                .map(ToolGuardPolicy::normalize)
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
        validateAuthorization();
        return delegate.call(toolInput);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        validateAuthorization();
        return delegate.call(toolInput, toolContext);
    }

    private void validateAuthorization() {
        String toolName = resolveToolName();
        if (StringUtils.isBlank(toolName)) {
            throw new ToolGuardException("TOOL_NAME_INVALID", "工具名称为空，已拒绝调用。");
        }
        String normalizedToolName = ToolGuardPolicy.normalize(toolName);
        if (!allowedToolNames.contains(normalizedToolName)) {
            throw new ToolGuardException("TOOL_NOT_AUTHORIZED", "工具不在本轮授权集合内，已拒绝调用。");
        }
        if (ToolGuardPolicy.isBlocked(toolName)) {
            throw new ToolGuardException("TOOL_FORBIDDEN", ToolGuardPolicy.describe(toolName));
        }
    }

    private String resolveToolName() {
        if (delegate == null || delegate.getToolDefinition() == null) {
            return "";
        }
        return delegate.getToolDefinition().name();
    }

}
