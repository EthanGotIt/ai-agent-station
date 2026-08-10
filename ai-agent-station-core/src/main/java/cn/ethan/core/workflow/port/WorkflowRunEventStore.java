package cn.ethan.core.workflow.port;

import cn.ethan.core.workflow.model.WorkflowRunEventModel;

/**
 * Workflow 运行事件存储端口：保存追加式审计事件。
 *
 * @author ethan
 * @date 2026-08-07
 */
public interface WorkflowRunEventStore {

    void append(WorkflowRunEventModel event);
}
