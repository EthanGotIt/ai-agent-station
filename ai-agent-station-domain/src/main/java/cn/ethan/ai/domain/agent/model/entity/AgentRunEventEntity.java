package cn.ethan.ai.domain.agent.model.entity;

import lombok.Builder;
import lombok.Data;

/**
 * Agent 单次运行事件实体
 */
@Data
@Builder
public class AgentRunEventEntity {

    private String runId;

    private String eventType;

    private String stepId;

    private Integer step;

    private Long startTime;

    private Long endTime;

    private Long costMillis;

    private String summary;

    private String error;

}
