package cn.ethan.core.workflow.node;

import cn.ethan.core.workflow.model.NodeResultModel;
import cn.ethan.core.workflow.model.WorkflowContextModel;

/**
 * Workflow 节点：定义服务端流程图中的原子执行单元。
 *
 * @author ethan
 * @date 2026-08-05
 */
@FunctionalInterface
public interface WorkflowNode {

    NodeResultModel execute(WorkflowContextModel context);
}
