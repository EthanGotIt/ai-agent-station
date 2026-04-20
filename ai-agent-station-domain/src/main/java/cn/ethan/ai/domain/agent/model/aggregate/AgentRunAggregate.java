package cn.ethan.ai.domain.agent.model.aggregate;

import cn.ethan.ai.domain.agent.model.entity.AgentRunTraceEntity;
import cn.ethan.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.ethan.ai.domain.agent.model.valobj.AgentPlanVO;
import cn.ethan.ai.domain.agent.model.valobj.ContextWindowGuardVO;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;

/**
 * Agent 单次运行聚合根
 */
public class AgentRunAggregate {

    @Getter
    private final ExecuteCommandEntity command;

    @Getter
    private final AgentRunTraceEntity trace;

    @Getter
    private final ContextWindowGuardVO contextWindowGuard;

    private final Map<String, String> stepOutputs = new LinkedHashMap<>();

    @Getter
    private AgentPlanVO plan;

    private AgentRunAggregate(ExecuteCommandEntity command) {
        this.command = command;
        this.trace = new AgentRunTraceEntity();
        this.contextWindowGuard = new ContextWindowGuardVO();
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

    public void bindExecutionPlan(AgentPlanVO plan) {
        this.plan = plan;
    }

    public void recordStepOutput(String stepId, String output) {
        stepOutputs.put(stepId, output);
    }

    public Map<String, String> stepOutputs() {
        return Collections.unmodifiableMap(stepOutputs);
    }
}
