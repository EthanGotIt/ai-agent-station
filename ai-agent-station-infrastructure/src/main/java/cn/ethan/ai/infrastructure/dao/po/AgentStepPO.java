package cn.ethan.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentStepPO {

    private Long id;
    private String runId;
    private String stepId;
    private String stepName;
    private Integer stepOrder;
    private String stepType;
    private String status;
    private String outputSummary;
    private String errorMessage;
    private Long costMillis;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
