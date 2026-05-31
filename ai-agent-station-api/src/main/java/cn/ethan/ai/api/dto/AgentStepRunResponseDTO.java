package cn.ethan.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentStepRunResponseDTO {

    private String stepId;

    private String stepName;

    private Integer stepOrder;

    private String stepType;

    private String status;

    private String outputSummary;

    private String errorMessage;

    private String terminalReason;

    private Long costMillis;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

}
