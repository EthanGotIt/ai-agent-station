package cn.ethan.core.workflow.port;

import cn.ethan.core.workflow.model.WorkflowContextModel;
import cn.ethan.core.workflow.model.WorkflowDescriptorModel;
import cn.ethan.core.workflow.model.WorkflowResultModel;

/**
 * Workflow 执行契约：统一暴露流程标识与确定性执行入口。
 *
 * @author ethan
 * @date 2026-08-06
 */
public interface WorkflowExecutor {

    String workflowId();

    default WorkflowDescriptorModel descriptor() {
        return new WorkflowDescriptorModel(workflowId(), workflowId(), "v1", java.util.List.of());
    }

    WorkflowResultModel execute(WorkflowContextModel context);
}
