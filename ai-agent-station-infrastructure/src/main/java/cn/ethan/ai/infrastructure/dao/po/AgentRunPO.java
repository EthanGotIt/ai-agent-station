package cn.ethan.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 智能体运行记录
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentRunPO {

    private Long id;

    private String runId;

    private String turnId;

    private String caseId;

    private String agentId;

    private String triggerType;

    private Integer attemptNo;

    private String status;

    private String finalSummary;

    private String errorMessage;

    private String cancelReason;

    private String checkpointBefore;

    private String checkpointAfter;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

}
