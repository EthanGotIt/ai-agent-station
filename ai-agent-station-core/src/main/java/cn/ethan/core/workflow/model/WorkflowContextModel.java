package cn.ethan.core.workflow.model;

import cn.ethan.core.agent.model.AgentRequestModel;
import cn.ethan.core.agent.support.CancellationToken;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Workflow 上下文模型：在节点之间传递不可变的请求状态和值对象。
 *
 * @author ethan
 * @date 2026-08-05
 */
public record WorkflowContextModel(
        AgentRequestModel request,
        String userId,
        CancellationToken cancellationToken,
        Map<String, Object> values
) {

    public WorkflowContextModel {
        request = Objects.requireNonNull(request, "request is required");
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId is required");
        }
        cancellationToken = Objects.requireNonNull(
                cancellationToken,
                "cancellationToken is required"
        );
        values = values == null ? Map.of() : Map.copyOf(values);
    }

    public WorkflowContextModel with(String key, Object value) {
        Map<String, Object> copy = new HashMap<>(values);
        copy.put(key, value);
        return new WorkflowContextModel(request, userId, cancellationToken, copy);
    }

    public Object value(String key) {
        return values.get(key);
    }
}
