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
public class AgentTurnPO {

    private Long id;

    private String turnId;

    private String caseId;

    private String sessionId;

    private String actorId;

    private String turnType;

    private Integer attemptNo;

    private String inputSummary;

    private String outputSummary;

    private String status;

    private String checkpointBefore;

    private String checkpointAfter;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
