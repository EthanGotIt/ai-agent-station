package cn.ethan.ai.domain.agent.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 一次外部交互记录，例如用户发起、补充信息或人工审批。
 *
 * <p>Run 合并进 Turn 后，同时作为一次执行尝试记录。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentTurnRecord {

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
