package cn.ethan.core.workflow.exception;

/**
 * Workflow 运行冲突异常：表示检查点或乐观锁版本已被其他请求推进。
 *
 * @author ethan
 * @date 2026-08-07
 */
public final class WorkflowRunConflictException extends RuntimeException {

    public WorkflowRunConflictException(String message) {
        super(message);
    }
}
