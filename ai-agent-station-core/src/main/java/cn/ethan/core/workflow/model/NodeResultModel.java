package cn.ethan.core.workflow.model;

import cn.ethan.core.workflow.enums.NodeResultTypeEnum;

/**
 * 节点结果模型：统一描述确定性 Workflow 节点的执行结果。
 *
 * @author ethan
 * @date 2026-08-05
 */
public record NodeResultModel(
        NodeResultTypeEnum type,
        WorkflowContextModel context,
        String nextNode,
        String message,
        WorkflowQuestionModel question
) {

    public static NodeResultModel continueTo(WorkflowContextModel context, String nextNode) {
        return new NodeResultModel(NodeResultTypeEnum.CONTINUE, context, nextNode, null, null);
    }

    public static NodeResultModel retry(WorkflowContextModel context, String fallbackNode, String message) {
        return new NodeResultModel(NodeResultTypeEnum.RETRY, context, fallbackNode, message, null);
    }

    public static NodeResultModel waitingUserInput(
            WorkflowContextModel context,
            String checkpointId,
            String message,
            WorkflowQuestionModel question
    ) {
        return new NodeResultModel(
                NodeResultTypeEnum.WAITING_USER_INPUT,
                context,
                checkpointId,
                message,
                question
        );
    }

    public static NodeResultModel complete(WorkflowContextModel context, String message) {
        return new NodeResultModel(NodeResultTypeEnum.COMPLETE, context, null, message, null);
    }

    public static NodeResultModel failed(WorkflowContextModel context, String message) {
        return new NodeResultModel(NodeResultTypeEnum.FAILED, context, null, message, null);
    }
}
