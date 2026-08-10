package cn.ethan.core.workflow.model;

import cn.ethan.core.workflow.enums.WorkflowStatusEnum;
import cn.ethan.core.agent.model.StructuredResultModel;

/**
 * Workflow 结果模型：统一承载图执行器返回的完成、待输入或失败状态。
 *
 * @author ethan
 * @date 2026-08-05
 */
public record WorkflowResultModel(
        WorkflowStatusEnum status,
        String workflowId,
        String resumeNode,
        WorkflowQuestionModel question,
        String content,
        StructuredResultModel structuredResult,
        WorkflowContextModel context
) {

    public static WorkflowResultModel completed(String workflowId, String content,
                                                WorkflowContextModel context) {
        StructuredResultModel structuredResult = context != null
                && context.value("structuredResult") instanceof StructuredResultModel result
                ? result
                : null;
        return completed(workflowId, content, structuredResult, context);
    }

    public static WorkflowResultModel completed(String workflowId, String content,
                                                StructuredResultModel structuredResult,
                                                WorkflowContextModel context) {
        return new WorkflowResultModel(WorkflowStatusEnum.COMPLETED, workflowId, null,
                null, content, structuredResult, context);
    }

    public static WorkflowResultModel waitingUserInput(
            String workflowId,
            String checkpointId,
            String content,
            WorkflowQuestionModel question,
            WorkflowContextModel context
    ) {
        return new WorkflowResultModel(
                WorkflowStatusEnum.WAITING_USER_INPUT,
                workflowId,
                checkpointId,
                question,
                content,
                context != null && context.value("structuredResult") instanceof StructuredResultModel result
                        ? result
                        : null,
                context
        );
    }

    public static WorkflowResultModel failed(String workflowId, String content,
                                             WorkflowContextModel context) {
        return new WorkflowResultModel(WorkflowStatusEnum.FAILED, workflowId, null,
                null, content, null, context);
    }
}
