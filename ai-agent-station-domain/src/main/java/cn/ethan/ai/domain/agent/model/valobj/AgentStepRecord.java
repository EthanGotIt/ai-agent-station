package cn.ethan.ai.domain.agent.model.valobj;

import cn.ethan.ai.domain.agent.model.valobj.enums.AgentStepStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 一次 Graph Node、模型或工具执行记录。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentStepRecord {

    private String runId;
    private String stepId;
    private String stepName;
    private Integer stepOrder;
    private String stepType;
    private AgentStepStatus status;
    private String outputSummary;
    private String errorMessage;
    private Long costMillis;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
