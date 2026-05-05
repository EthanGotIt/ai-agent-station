package cn.ethan.ai.domain.agent.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Agent 执行流式结果实体
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AgentExecuteResultEntity {

    private static final String TYPE_ANALYSIS = "analysis";

    private static final String TYPE_EXECUTION = "execution";

    private static final String TYPE_SUPERVISION = "supervision";

    private static final String TYPE_SUMMARY = "summary";

    private static final String TYPE_ERROR = "error";

    private static final String TYPE_COMPLETE = "complete";

    private String type;

    private String subType;

    private Integer step;

    private String content;

    private Object payload;

    private Boolean completed;

    private Long timestamp;

    private String sessionId;

    private String runId;

    public static AgentExecuteResultEntity createAnalysisResult(Integer step, String content, String sessionId, String runId) {
        return create(TYPE_ANALYSIS, null, step, content, null, false, sessionId, runId);
    }

    public static AgentExecuteResultEntity createAnalysisSubResult(Integer step, String subType, String content, String sessionId, String runId) {
        return create(TYPE_ANALYSIS, subType, step, content, null, false, sessionId, runId);
    }

    public static AgentExecuteResultEntity createAnalysisSubResult(Integer step, String subType, String content, Object payload, String sessionId, String runId) {
        return create(TYPE_ANALYSIS, subType, step, content, payload, false, sessionId, runId);
    }

    public static AgentExecuteResultEntity createExecutionResult(Integer step, String content, String sessionId, String runId) {
        return create(TYPE_EXECUTION, null, step, content, null, false, sessionId, runId);
    }

    public static AgentExecuteResultEntity createExecutionSubResult(Integer step, String subType, String content, String sessionId, String runId) {
        return create(TYPE_EXECUTION, subType, step, content, null, false, sessionId, runId);
    }

    public static AgentExecuteResultEntity createExecutionSubResult(Integer step, String subType, String content, Object payload, String sessionId, String runId) {
        return create(TYPE_EXECUTION, subType, step, content, payload, false, sessionId, runId);
    }

    public static AgentExecuteResultEntity createSupervisionResult(Integer step, String content, String sessionId, String runId) {
        return create(TYPE_SUPERVISION, null, step, content, null, false, sessionId, runId);
    }

    public static AgentExecuteResultEntity createSupervisionSubResult(Integer step, String subType, String content, String sessionId, String runId) {
        return create(TYPE_SUPERVISION, subType, step, content, null, false, sessionId, runId);
    }

    public static AgentExecuteResultEntity createSummarySubResult(String subType, String content, String sessionId, String runId) {
        return create(TYPE_SUMMARY, subType, null, content, null, false, sessionId, runId);
    }

    public static AgentExecuteResultEntity createSummaryResult(String content, String sessionId, String runId) {
        return create(TYPE_SUMMARY, null, null, content, null, true, sessionId, runId);
    }

    public static AgentExecuteResultEntity createErrorResult(String content, String sessionId, String runId) {
        return create(TYPE_ERROR, null, null, content, null, true, sessionId, runId);
    }

    public static AgentExecuteResultEntity createCompleteResult(String sessionId, String runId) {
        return create(TYPE_COMPLETE, null, null, "执行完成", null, true, sessionId, runId);
    }

    private static AgentExecuteResultEntity create(String type,
                                                   String subType,
                                                   Integer step,
                                                   String content,
                                                   Object payload,
                                                   Boolean completed,
                                                   String sessionId,
                                                   String runId) {
        return AgentExecuteResultEntity.builder()
                .type(type)
                .subType(subType)
                .step(step)
                .content(content)
                .payload(payload)
                .completed(completed)
                .timestamp(System.currentTimeMillis())
                .sessionId(sessionId)
                .runId(runId)
                .build();
    }
}
