package cn.ethan.core.workflow.model;

import cn.ethan.core.workflow.node.WorkflowNode;

import java.util.Map;

/**
 * Workflow 定义模型：描述服务端注册的流程图及其执行限制。
 *
 * @author ethan
 * @date 2026-08-05
 */
public record WorkflowDefinitionModel(
        String id,
        String initialNode,
        Map<String, WorkflowNode> nodes,
        int maxTransitions,
        int maxRetriesPerNode
) {

    public WorkflowDefinitionModel {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("workflow id is required");
        }
        if (nodes == null || initialNode == null || !nodes.containsKey(initialNode)) {
            throw new IllegalArgumentException("initial node is missing");
        }
        nodes = Map.copyOf(nodes);
        maxTransitions = maxTransitions <= 0 ? 8 : Math.min(maxTransitions, 8);
        maxRetriesPerNode = maxRetriesPerNode < 0 ? 0 : Math.min(maxRetriesPerNode, 2);
    }
}
