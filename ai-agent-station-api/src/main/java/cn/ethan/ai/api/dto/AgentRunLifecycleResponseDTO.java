package cn.ethan.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentRunLifecycleResponseDTO {

    private String runtimePhase;

    private String currentStepId;

    private String terminalReason;

    private Integer trackedStepCount;

    private Integer completedStepCount;

    private Integer failedStepCount;

    private Integer skippedStepCount;

    private Integer cancelledStepCount;

    private Boolean contextCompacted;
}
