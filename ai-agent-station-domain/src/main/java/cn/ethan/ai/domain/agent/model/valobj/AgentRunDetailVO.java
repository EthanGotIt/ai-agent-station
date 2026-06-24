package cn.ethan.ai.domain.agent.model.valobj;

import cn.ethan.ai.domain.agent.model.valobj.enums.AgentRunStatusEnumVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * 运行详情
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentRunDetailVO {

    private String runId;

    private String agentId;

    private String sessionId;

    private String userMessage;

    private AgentRunStatusEnumVO status;

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

    private AgentRunLifecycleVO lifecycle;

    @Builder.Default
    private List<AgentStepRunRecordVO> steps = Collections.emptyList();

}
