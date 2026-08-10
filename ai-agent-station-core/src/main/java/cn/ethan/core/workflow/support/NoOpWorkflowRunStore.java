package cn.ethan.core.workflow.support;

import cn.ethan.core.workflow.model.WorkflowRunModel;
import cn.ethan.core.workflow.port.WorkflowRunStore;

import java.util.Optional;

/**
 * 空 Workflow 运行存储：保持未装配持久化存储的纯 Core 测试构造器可用。
 *
 * @author ethan
 * @date 2026-08-07
 */
public final class NoOpWorkflowRunStore implements WorkflowRunStore {

    @Override
    public void create(WorkflowRunModel run) {
        throw new UnsupportedOperationException("workflow resume store is not configured");
    }

    @Override
    public Optional<WorkflowRunModel> findOwned(String runId, String userId, String sessionId) {
        return Optional.empty();
    }

    @Override
    public boolean compareAndSet(WorkflowRunModel expected, WorkflowRunModel updated) {
        return false;
    }
}
