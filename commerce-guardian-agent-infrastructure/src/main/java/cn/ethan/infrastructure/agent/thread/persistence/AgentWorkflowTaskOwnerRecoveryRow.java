package cn.ethan.infrastructure.agent.thread.persistence;

/**
 * 类型职责：承接启动恢复查询中 owner Turn 与 WorkflowTask 的最小联结事实。
 *
 * @author ethan
 * @date 2026-08-22
 */
public final class AgentWorkflowTaskOwnerRecoveryRow {

    private String turnId;
    private String userId;
    private String workflowTaskId;
    private String workflowTaskStatus;
    private Integer openInteraction;

    public String getTurnId() {
        return turnId;
    }

    public void setTurnId(String turnId) {
        this.turnId = turnId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getWorkflowTaskId() {
        return workflowTaskId;
    }

    public void setWorkflowTaskId(String workflowTaskId) {
        this.workflowTaskId = workflowTaskId;
    }

    public String getWorkflowTaskStatus() {
        return workflowTaskStatus;
    }

    public void setWorkflowTaskStatus(String workflowTaskStatus) {
        this.workflowTaskStatus = workflowTaskStatus;
    }

    public Integer getOpenInteraction() {
        return openInteraction;
    }

    public void setOpenInteraction(Integer openInteraction) {
        this.openInteraction = openInteraction;
    }
}
