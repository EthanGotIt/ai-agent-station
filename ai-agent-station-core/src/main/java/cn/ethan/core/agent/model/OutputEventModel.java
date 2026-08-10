package cn.ethan.core.agent.model;

import cn.ethan.core.agent.enums.OutputEventTypeEnum;
import cn.ethan.core.workflow.model.WorkflowQuestionModel;
import cn.ethan.core.workflow.model.WorkflowRunModel;

/**
 * 输出事件模型：统一同步可观测记录和 SSE 推送使用的内部事件数据。
 *
 * @author ethan
 * @date 2026-08-05
 */
public record OutputEventModel(
        OutputEventTypeEnum type,
        String value,
        StructuredResultModel structuredResult,
        WorkflowQuestionModel question,
        WorkflowRunModel workflowRun,
        ToolInterventionModel intervention
) {

    public OutputEventModel(OutputEventTypeEnum type, String value) {
        this(type, value, null, null, null, null);
    }

    public static OutputEventModel result(StructuredResultModel structuredResult) {
        return new OutputEventModel(OutputEventTypeEnum.RESULT, "", structuredResult, null, null, null);
    }

    public static OutputEventModel workflowQuestion(
            WorkflowQuestionModel question,
            WorkflowRunModel workflowRun
    ) {
        return new OutputEventModel(OutputEventTypeEnum.WORKFLOW_QUESTION, "", null, question, workflowRun, null);
    }

    public static OutputEventModel intervention(ToolInterventionModel intervention) {
        return new OutputEventModel(OutputEventTypeEnum.INTERVENTION, "", null, null, null, intervention);
    }
}
