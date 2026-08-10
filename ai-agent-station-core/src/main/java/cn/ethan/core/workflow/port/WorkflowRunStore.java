package cn.ethan.core.workflow.port;

import cn.ethan.core.workflow.model.WorkflowRunModel;

import java.util.Optional;

/**
 * Workflow 运行存储端口：提供按归属读取和乐观锁状态更新能力。
 *
 * @author ethan
 * @date 2026-08-07
 */
public interface WorkflowRunStore {

    void create(WorkflowRunModel run);

    Optional<WorkflowRunModel> findOwned(String runId, String userId, String sessionId);

    boolean compareAndSet(WorkflowRunModel expected, WorkflowRunModel updated);
}
