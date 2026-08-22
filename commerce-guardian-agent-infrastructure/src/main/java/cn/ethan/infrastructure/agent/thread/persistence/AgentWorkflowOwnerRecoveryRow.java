package cn.ethan.infrastructure.agent.thread.persistence;

/**
 * 类型职责：承接启动恢复查询中 owner Turn 与 WorkflowRun 的最小联结事实。
 *
 * @author ethan
 * @date 2026-08-22
 */
public final class AgentWorkflowOwnerRecoveryRow {

    private String turnId;
    private String userId;
    private String workflowRunId;
    private String workflowRunStatus;
    private Integer openQuestion;

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

    public String getWorkflowRunId() {
        return workflowRunId;
    }

    public void setWorkflowRunId(String workflowRunId) {
        this.workflowRunId = workflowRunId;
    }

    public String getWorkflowRunStatus() {
        return workflowRunStatus;
    }

    public void setWorkflowRunStatus(String workflowRunStatus) {
        this.workflowRunStatus = workflowRunStatus;
    }

    public Integer getOpenQuestion() {
        return openQuestion;
    }

    public void setOpenQuestion(Integer openQuestion) {
        this.openQuestion = openQuestion;
    }
}
