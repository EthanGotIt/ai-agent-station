package cn.ethan.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentRunDetailResponseDTO {

    private String runId;

    private String agentId;

    private String sessionId;

    private String userMessage;

    private String status;

    private String finalSummary;

    private String errorMessage;

    private String cancelReason;

    private Integer contextOriginalChars;

    private Integer contextCompressedChars;

    private String contextSummary;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private AgentRunLifecycleResponseDTO lifecycle;

    private AgentContextBoundaryResponseDTO contextBoundary;

    @Builder.Default
    private List<AgentStepRunResponseDTO> steps = Collections.emptyList();

}
