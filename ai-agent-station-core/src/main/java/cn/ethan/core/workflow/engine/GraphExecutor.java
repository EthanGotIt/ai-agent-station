package cn.ethan.core.workflow.engine;

import cn.ethan.core.workflow.model.NodeResultModel;
import cn.ethan.core.workflow.model.WorkflowContextModel;
import cn.ethan.core.workflow.model.WorkflowDefinitionModel;
import cn.ethan.core.workflow.model.WorkflowResultModel;
import cn.ethan.core.workflow.node.WorkflowNode;

import java.util.HashMap;
import java.util.Map;

/**
 * 图执行器：执行服务端定义的确定性流程图，模型不得提供节点或转移关系。
 *
 * @author ethan
 * @date 2026-08-05
 */
public final class GraphExecutor {

    public WorkflowResultModel execute(WorkflowDefinitionModel definition,
                                       WorkflowContextModel initialContext) {
        return execute(definition, initialContext, definition.initialNode());
    }

    public WorkflowResultModel execute(
            WorkflowDefinitionModel definition,
            WorkflowContextModel initialContext,
            String startNode
    ) {
        String nodeId = startNode == null || startNode.isBlank() ? definition.initialNode() : startNode;
        WorkflowContextModel context = initialContext;
        Map<String, Integer> retries = new HashMap<>();

        for (int transition = 0; transition < definition.maxTransitions(); transition++) {
            context.cancellationToken().throwIfCancelled();
            WorkflowNode node = definition.nodes().get(nodeId);
            if (node == null) {
                return WorkflowResultModel.failed(definition.id(), "工作流节点不存在：" + nodeId, context);
            }

            NodeResultModel result;
            try {
                result = node.execute(context);
                context.cancellationToken().throwIfCancelled();
            } catch (RuntimeException exception) {
                if (exception instanceof java.util.concurrent.CancellationException cancellation) {
                    throw cancellation;
                }
                int attempt = retries.merge(nodeId, 1, Integer::sum);
                if (attempt <= definition.maxRetriesPerNode()) {
                    continue;
                }
                return WorkflowResultModel.failed(definition.id(), "工作流执行失败：" + nodeId, context);
            }
            context = result.context() == null ? context : result.context();

            switch (result.type()) {
                case CONTINUE -> nodeId = result.nextNode();
                case RETRY -> {
                    int attempt = retries.merge(nodeId, 1, Integer::sum);
                    if (attempt <= definition.maxRetriesPerNode()) {
                        continue;
                    }
                    if (result.nextNode() != null && !result.nextNode().equals(nodeId)) {
                        nodeId = result.nextNode();
                        retries.remove(nodeId);
                    } else {
                        return WorkflowResultModel.failed(definition.id(), result.message(), context);
                    }
                }
                case WAITING_USER_INPUT -> {
                    return WorkflowResultModel.waitingUserInput(
                            definition.id(),
                            result.nextNode(),
                            result.message(),
                            result.question(),
                            context
                    );
                }
                case COMPLETE -> {
                    return WorkflowResultModel.completed(definition.id(), result.message(), context);
                }
                case FAILED -> {
                    return WorkflowResultModel.failed(definition.id(), result.message(), context);
                }
            }
        }
        return WorkflowResultModel.failed(definition.id(), "工作流超过最大步数", context);
    }
}
