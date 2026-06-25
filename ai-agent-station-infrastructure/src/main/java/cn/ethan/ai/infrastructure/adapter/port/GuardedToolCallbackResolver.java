package cn.ethan.ai.infrastructure.adapter.port;

import cn.ethan.ai.domain.agent.service.execute.runtime.ToolGuardPolicy;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.resolution.DelegatingToolCallbackResolver;
import org.springframework.ai.tool.resolution.StaticToolCallbackResolver;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 带安全包装的 ToolCallbackResolver。
 *
 * <p>将多个 MCP 来源的工具通过 {@link DelegatingToolCallbackResolver} 串联，
 * 每个工具在返回前经 {@link ToolGuardPolicy} 过滤并包装为 {@link GuardedToolCallback}。</p>
 */
public class GuardedToolCallbackResolver implements ToolCallbackResolver {

    private final DelegatingToolCallbackResolver delegate;

    /**
     * 从多个工具列表构造，每个列表会被包装为一个只读的 {@link StaticToolCallbackResolver}。
     */
    public GuardedToolCallbackResolver(List<List<ToolCallback>> mcpToolCallbackGroups) {
        List<ToolCallbackResolver> resolvers = new ArrayList<>();
        for (List<ToolCallback> group : mcpToolCallbackGroups) {
            if (group == null || group.isEmpty()) {
                continue;
            }
            Set<String> allowedNames = safeToolNames(group);
            List<ToolCallback> guarded = group.stream()
                    .filter(callback -> {
                        if (callback == null) return false;
                        callback.getToolDefinition();
                        callback.getToolDefinition().name();
                        return true;
                    })
                    .filter(callback -> {
                        String name = ToolGuardPolicy.normalize(callback.getToolDefinition().name());
                        return ToolGuardPolicy.isReadOnlyEvidenceTool(name)
                                && !ToolGuardPolicy.isBlocked(name);
                    })
                    .map(callback -> (ToolCallback) new GuardedToolCallback(callback, allowedNames))
                    .toList();
            if (!guarded.isEmpty()) {
                resolvers.add(new StaticToolCallbackResolver(guarded));
            }
        }
        this.delegate = new DelegatingToolCallbackResolver(resolvers);
    }

    @Override
    public ToolCallback resolve(@NonNull String toolName) {
        return delegate.resolve(toolName);
    }

    private static Set<String> safeToolNames(List<ToolCallback> callbacks) {
        return callbacks.stream()
                .filter(callback -> {
                    if (callback == null) return false;
                    callback.getToolDefinition();
                    callback.getToolDefinition().name();
                    return true;
                })
                .map(callback -> callback.getToolDefinition().name().trim().toLowerCase(Locale.ROOT))
                .map(ToolGuardPolicy::normalize)
                .collect(Collectors.toSet());
    }
}
