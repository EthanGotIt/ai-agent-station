package cn.ethan.dto;

import cn.ethan.core.workflow.model.WorkflowRunModel;

/**
 * Workflow 运行 DTO：向客户端公开确认所需的最小运行标识和允许动作。
 *
 * @author ethan
 * @date 2026-08-07
 */
public record AgentChatWorkflowRunDto(
        String runId,
        String checkpointId,
        long version,
        String status
) {

    public static AgentChatWorkflowRunDto from(WorkflowRunModel run) {
        if (run == null) {
            return null;
        }
        return new AgentChatWorkflowRunDto(
                run.runId(),
                run.checkpointId(),
                run.version(),
                run.status().name()
        );
    }
}
