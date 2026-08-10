package cn.ethan.core.workflow.exception;

/**
 * Workflow 运行不存在异常：同时用于隐藏非所属用户的运行实例。
 *
 * @author ethan
 * @date 2026-08-07
 */
public final class WorkflowRunNotFoundException extends RuntimeException {

    public WorkflowRunNotFoundException(String runId) {
        super("workflow run does not exist: " + runId);
    }
}
