package cn.ethan.ai.domain.agent.model.aggregate;

import cn.ethan.ai.domain.agent.model.entity.AgentRunTraceEntity;
import cn.ethan.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.ethan.ai.domain.agent.model.valobj.AgentRunRecordVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.AgentRunStatusEnumVO;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.time.LocalDateTime;
import lombok.Getter;

/**
 * Agent 单次运行聚合根
 */
public class AgentRunAggregate {

    @Getter
    private final ExecuteCommandEntity command;

    @Getter
    private final AgentRunTraceEntity trace;

    private final Map<String, String> stepOutputs = new LinkedHashMap<>();

    @Getter
    private AgentRunStatusEnumVO status;

    @Getter
    private String finalSummary;

    @Getter
    private String errorMessage;

    @Getter
    private String cancelReason;

    @Getter
    private String sessionContextSummary;

    @Getter
    private LocalDateTime startTime;

    @Getter
    private LocalDateTime endTime;

    private AgentRunAggregate(ExecuteCommandEntity command) {
        this.command = command;
        this.trace = new AgentRunTraceEntity();
        this.status = AgentRunStatusEnumVO.INIT;
    }

    public static AgentRunAggregate create(ExecuteCommandEntity command) {
        return new AgentRunAggregate(command);
    }

    public String runId() {
        return trace.getRunId();
    }

    public int maxStepOrDefault(int defaultMaxStep) {
        Integer maxStep = command.getMaxStep();
        return maxStep == null || maxStep <= 0 ? defaultMaxStep : maxStep;
    }

    public void bindSessionContextSummary(String sessionContextSummary) {
        this.sessionContextSummary = sessionContextSummary;
    }

    public void recordStepOutput(String stepId, String output) {
        stepOutputs.put(stepId, output);
    }

    public Map<String, String> stepOutputs() {
        return Collections.unmodifiableMap(stepOutputs);
    }

    public void markRunning() {
        this.status = AgentRunStatusEnumVO.RUNNING;
        this.startTime = LocalDateTime.now();
    }

    public void markSuccess(String summary) {
        this.status = AgentRunStatusEnumVO.SUCCESS;
        this.finalSummary = summary;
        this.endTime = LocalDateTime.now();
    }

    public void markFailed(String error) {
        this.status = AgentRunStatusEnumVO.FAILED;
        this.errorMessage = error;
        this.endTime = LocalDateTime.now();
    }

    public void markCancelled(String reason) {
        this.status = AgentRunStatusEnumVO.CANCELLED;
        this.cancelReason = reason;
        this.endTime = LocalDateTime.now();
    }

    public boolean isCancelled() {
        return AgentRunStatusEnumVO.CANCELLED == status;
    }

    public AgentRunRecordVO toRecord() {
        return AgentRunRecordVO.builder()
                .runId(runId())
                .agentId(command.getAiAgentId())
                .sessionId(command.getSessionId())
                .userMessage(command.getMessage())
                .status(status)
                .finalSummary(finalSummary)
                .errorMessage(errorMessage)
                .cancelReason(cancelReason)
                .sessionContextSummary(sessionContextSummary)
                .startTime(startTime)
                .endTime(endTime)
                .build();
    }
}
