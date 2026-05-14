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
public class AiAgentRun {

    private Long id;

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

}
