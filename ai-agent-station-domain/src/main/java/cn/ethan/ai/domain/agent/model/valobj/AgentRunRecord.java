package cn.ethan.ai.domain.agent.model.valobj;

import cn.ethan.ai.domain.agent.model.valobj.enums.AgentRunStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 运行记录
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentRunRecord {

    private String runId;

    private String agentId;

    private String sessionId;

    private String userMessage;

    private AgentRunStatus status;

    private String finalSummary;

    private String errorMessage;

    private String cancelReason;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

}
