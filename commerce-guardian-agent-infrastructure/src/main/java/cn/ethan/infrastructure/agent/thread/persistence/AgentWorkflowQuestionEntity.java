package cn.ethan.infrastructure.agent.thread.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * 类型职责：映射可跨刷新和重启恢复的 Workflow QuestionCard。
 *
 * @author ethan
 * @date 2026-08-19
 */
@TableName("AGENT_WORKFLOW_QUESTION")
public final class AgentWorkflowQuestionEntity {

    @TableId(value = "QUESTION_ID", type = IdType.INPUT)
    private String questionId;
    @TableField("RUN_ID")
    private String runId;
    @TableField("THREAD_ID")
    private String threadId;
    @TableField("TURN_ID")
    private String turnId;
    @TableField("USER_ID")
    private String userId;
    @TableField("CHECKPOINT_ID")
    private String checkpointId;
    @TableField("VERSION_NO")
    private Long versionNo;
    @TableField("ANSWER_TURN_ID")
    private String answerTurnId;
    @TableField("ANSWER_ENQUEUE_STATUS")
    private String answerEnqueueStatus;
    @TableField("TITLE")
    private String title;
    @TableField("PROMPT")
    private String prompt;
    @TableField("FIELDS_JSON")
    private String fieldsJson;
    @TableField("STATUS")
    private String status;
    @TableField("CREATED_AT")
    private Instant createdAt;
    @TableField("ANSWERED_AT")
    private Instant answeredAt;

    public AgentWorkflowQuestionEntity() {
    }

    public String getQuestionId() { return questionId; }
    public void setQuestionId(String questionId) { this.questionId = questionId; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getThreadId() { return threadId; }
    public void setThreadId(String threadId) { this.threadId = threadId; }
    public String getTurnId() { return turnId; }
    public void setTurnId(String turnId) { this.turnId = turnId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getCheckpointId() { return checkpointId; }
    public void setCheckpointId(String checkpointId) { this.checkpointId = checkpointId; }
    public Long getVersionNo() { return versionNo; }
    public void setVersionNo(Long versionNo) { this.versionNo = versionNo; }
    public String getAnswerTurnId() { return answerTurnId; }
    public void setAnswerTurnId(String answerTurnId) { this.answerTurnId = answerTurnId; }
    public String getAnswerEnqueueStatus() { return answerEnqueueStatus; }
    public void setAnswerEnqueueStatus(String answerEnqueueStatus) { this.answerEnqueueStatus = answerEnqueueStatus; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }
    public String getFieldsJson() { return fieldsJson; }
    public void setFieldsJson(String fieldsJson) { this.fieldsJson = fieldsJson; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getAnsweredAt() { return answeredAt; }
    public void setAnsweredAt(Instant answeredAt) { this.answeredAt = answeredAt; }
}
