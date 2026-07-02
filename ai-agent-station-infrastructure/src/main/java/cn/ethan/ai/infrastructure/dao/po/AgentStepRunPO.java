package cn.ethan.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 智能体步骤运行记录
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentStepRunPO {

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
