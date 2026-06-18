package cn.ethan.ai.domain.agent.model.valobj;

import cn.ethan.ai.domain.agent.model.valobj.enums.AgentRunStatusEnumVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.AgentStepRunStatusEnumVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.StringUtils;

import java.util.Collections;
import java.util.List;

/**
 * Agent Run 生命周期视图，由运行主表与步骤表派生，不额外引入持久化结构。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentRunLifecycleVO {

    private String runtimePhase;

    private String currentStepId;

    private String terminalReason;

    private Integer trackedStepCount;

    private Integer completedStepCount;

    private Integer failedStepCount;

    private Integer skippedStepCount;

    private Integer cancelledStepCount;

    private Boolean contextCompacted;

    public static AgentRunLifecycleVO from(AgentRunStatusEnumVO status,
                                           String errorMessage,
                                           String cancelReason,
                                           Integer contextOriginalChars,
                                           Integer contextCompressedChars,
                                           String contextSummary,
                                           List<AgentStepRunRecordVO> steps) {
        List<AgentStepRunRecordVO> safeSteps = steps == null ? Collections.emptyList() : steps;
        AgentStepRunRecordVO runningStep = firstStepByStatus(safeSteps, AgentStepRunStatusEnumVO.RUNNING);
        AgentStepRunRecordVO failedStep = firstStepByStatus(safeSteps, AgentStepRunStatusEnumVO.FAILED);

        return AgentRunLifecycleVO.builder()
                .runtimePhase(resolveRuntimePhase(status, runningStep))
                .currentStepId(runningStep == null ? "" : runningStep.getStepId())
                .terminalReason(resolveTerminalReason(status, errorMessage, cancelReason, failedStep))
                .trackedStepCount(safeSteps.size())
                .completedStepCount(countByStatus(safeSteps, AgentStepRunStatusEnumVO.SUCCESS))
                .failedStepCount(countByStatus(safeSteps, AgentStepRunStatusEnumVO.FAILED))
                .skippedStepCount(countByStatus(safeSteps, AgentStepRunStatusEnumVO.SKIPPED))
                .cancelledStepCount(countByStatus(safeSteps, AgentStepRunStatusEnumVO.CANCELLED))
                .contextCompacted(isContextCompacted(contextOriginalChars, contextCompressedChars, contextSummary))
                .build();
    }

    private static AgentStepRunRecordVO firstStepByStatus(List<AgentStepRunRecordVO> steps, AgentStepRunStatusEnumVO status) {
        return steps.stream()
                .filter(step -> step != null && status == step.getStatus())
                .findFirst()
                .orElse(null);
    }

    private static int countByStatus(List<AgentStepRunRecordVO> steps, AgentStepRunStatusEnumVO status) {
        return (int) steps.stream()
                .filter(step -> step != null && status == step.getStatus())
                .count();
    }

    private static String resolveRuntimePhase(AgentRunStatusEnumVO status, AgentStepRunRecordVO runningStep) {
        if (AgentRunStatusEnumVO.SUCCESS == status) {
            return "COMPLETED";
        }
        if (AgentRunStatusEnumVO.FAILED == status) {
            return "FAILED";
        }
        if (AgentRunStatusEnumVO.CANCELLED == status) {
            return "CANCELLED";
        }
        if (runningStep == null) {
            return AgentRunStatusEnumVO.INIT == status ? "CREATED" : "RUNNING";
        }

        String stepId = StringUtils.defaultString(runningStep.getStepId());
        if ("harness_root".equals(stepId)) {
            return "INITIALIZING";
        }
        if ("harness_tool_routing".equals(stepId)) {
            return "TOOL_ROUTING";
        }
        if (stepId.startsWith("harness_action_")) {
            return "DECIDING";
        }
        if (stepId.startsWith("rag_")) {
            return "RAG_RETRIEVING";
        }
        return "EXECUTING";
    }

    private static String resolveTerminalReason(AgentRunStatusEnumVO status,
                                                String errorMessage,
                                                String cancelReason,
                                                AgentStepRunRecordVO failedStep) {
        if (AgentRunStatusEnumVO.FAILED == status) {
            if (failedStep != null && StringUtils.isNotBlank(failedStep.getErrorMessage())) {
                return failedStep.getStepName() + "：" + failedStep.getErrorMessage();
            }
            return StringUtils.defaultString(errorMessage);
        }
        if (AgentRunStatusEnumVO.CANCELLED == status) {
            return StringUtils.defaultString(cancelReason);
        }
        return "";
    }

    private static boolean isContextCompacted(Integer originalChars, Integer compressedChars, String contextSummary) {
        if (StringUtils.isNotBlank(contextSummary)) {
            return true;
        }
        if (originalChars == null || compressedChars == null) {
            return false;
        }
        return originalChars > 0 && compressedChars > 0 && compressedChars < originalChars;
    }
}
